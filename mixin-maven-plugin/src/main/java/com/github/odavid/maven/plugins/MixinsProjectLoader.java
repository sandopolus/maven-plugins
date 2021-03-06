package com.github.odavid.maven.plugins;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.MavenExecutionException;
import org.apache.maven.configuration.BeanConfigurationException;
import org.apache.maven.configuration.BeanConfigurationRequest;
import org.apache.maven.configuration.BeanConfigurator;
import org.apache.maven.configuration.DefaultBeanConfigurationRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.interpolation.ModelInterpolator;
import org.apache.maven.model.plugin.PluginConfigurationExpander;
import org.apache.maven.model.plugin.ReportingConverter;
import org.apache.maven.model.profile.DefaultProfileActivationContext;
import org.apache.maven.model.profile.ProfileInjector;
import org.apache.maven.model.profile.ProfileSelector;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.codehaus.plexus.logging.Logger;

public class MixinsProjectLoader {
	public static final String PLUGIN_GROUPID = "com.github.odavid.maven.plugins";
	public static final String PLUGIN_ARTIFACTID = "mixin-maven-plugin";

	private MavenSession mavenSession;
	private MavenProject mavenProject;
	private ProfileSelector profileSelector;
    private ProfileInjector profileInjector;
    private MixinModelMerger mixinModelMerger;
    private ModelInterpolator modelInterpolator;
    private PluginConfigurationExpander pluginConfigurationExpander;
	private BeanConfigurator beanConfigurator;
	private ReportingConverter reportingConverter;

    private DefaultModelBuildingRequest modelBuildingRequest = new DefaultModelBuildingRequest();
    private MixinModelCache mixinModelCache;
	private Logger logger;

	public MixinsProjectLoader(MavenSession mavenSession, MavenProject mavenProject, ModelInterpolator modelInterpolator, 
			PluginConfigurationExpander pluginConfigurationExpander, 
			BeanConfigurator beanConfigurator, Logger logger, 
			MixinModelCache mixinModelCache, ProfileSelector profileSelector, ProfileInjector profileInjector, 
			MixinModelMerger mixinModelMerger, ReportingConverter reportingConverter){
		this.mavenSession = mavenSession;
		this.mavenProject = mavenProject;
		this.modelInterpolator = modelInterpolator;
		this.pluginConfigurationExpander = pluginConfigurationExpander;
		this.beanConfigurator = beanConfigurator;
		this.logger = logger;
		this.mixinModelCache = mixinModelCache;
		this.profileSelector = profileSelector;
		this.profileInjector = profileInjector;
		this.mixinModelMerger = mixinModelMerger;
		this.reportingConverter = reportingConverter;
		
		ProjectBuildingRequest projectBuildingRequest = mavenSession.getProjectBuildingRequest();
		modelBuildingRequest.setActiveProfileIds(projectBuildingRequest.getActiveProfileIds());
		modelBuildingRequest.setInactiveProfileIds(projectBuildingRequest.getInactiveProfileIds());
		modelBuildingRequest.setBuildStartTime(projectBuildingRequest.getBuildStartTime());
	}
	
	public void mergeMixins() throws MavenExecutionException {
		List<Mixin> mixinList = new ArrayList<>();
		Map<String,Mixin> mixinMap = new HashMap<String, Mixin>();
		fillMixins(mixinList, mixinMap, mavenProject.getModel());
		MixinModelProblemCollector problems = new MixinModelProblemCollector();
		ModelBuildingRequest request = new DefaultModelBuildingRequest(modelBuildingRequest);
		request.setSystemProperties(mavenSession.getSystemProperties());
		request.setUserProperties(mavenSession.getUserProperties());

		Set<String> mixinProfiles = new HashSet<String>();
		for(Mixin mixin: mixinList){
			logger.debug(String.format("Merging mixin: %s into %s", mixin.getKey(), mavenProject.getFile()));
			Model mixinModel = mixinModelCache.getModel(mixin, mavenProject);
			if(mixin.isActivateProfiles()){
				logger.debug(String.format("Activating profiles in mixin: %s into %s", mixin.getKey(), mavenProject.getFile()));
				mixinModel = mixinModel.clone();
	            List<Profile> activePomProfiles =
	                    profileSelector.getActiveProfiles( mixinModel.getProfiles(), getProfileActivationContext(), problems );
				for(Profile profile: activePomProfiles){
					logger.debug(String.format("Activating profile %s in mixin: %s into %s", profile.getId(), mixin.getKey(), mavenProject.getFile()));
					profileInjector.injectProfile(mixinModel, profile, modelBuildingRequest, problems);
					mixinProfiles.add(profile.getId());
				}
			}
			//Need to convert old style reporting before merging the mixin, so the site plugin will be merged correctly
			reportingConverter.convertReporting(mixinModel, request, problems);
			mixin.merge(mixinModel, mavenProject, mavenSession, mixinModelMerger);
		}
		if(mixinList.size() > 0){
			//Apply the pluginManagement section on the plugins section
			mixinModelMerger.applyPluginManagementOnPlugins(mavenProject.getModel());

			modelInterpolator.interpolateModel(mavenProject.getModel(), mavenProject.getBasedir(), request, problems);
			pluginConfigurationExpander.expandPluginConfiguration(mavenProject.getModel(), request, problems);
			if(mavenProject.getInjectedProfileIds().containsKey(Profile.SOURCE_POM)){
				mavenProject.getInjectedProfileIds().get(Profile.SOURCE_POM).addAll(mixinProfiles);
			}else{
				mavenProject.getInjectedProfileIds().put(Profile.SOURCE_POM, new ArrayList<String>(mixinProfiles));
			}
			problems.checkErrors(mavenProject.getFile());
		}
	}
	
	private void fillMixins(List<Mixin> mixinList, Map<String,Mixin> mixinMap, Model model) throws MavenExecutionException {
		//Merge properties of current Project with mixin for interpolateModel to work correctly 
		model = model.clone();
		Properties origProperties = model.getProperties() != null ? model.getProperties() : new Properties();
		origProperties.putAll(mavenProject.getProperties());
		model.setProperties(origProperties);
		MixinModelProblemCollector problems = new MixinModelProblemCollector();

		ModelBuildingRequest request = new DefaultModelBuildingRequest(modelBuildingRequest);
		request.setSystemProperties(mavenSession.getSystemProperties());
		request.setUserProperties(mavenSession.getUserProperties());
		
		modelInterpolator.interpolateModel(model, mavenProject.getBasedir(), request, problems);
		if(model.getBuild() == null){
			model.setBuild(new Build());
		}
		List<Plugin> plugins = model.getBuild().getPlugins();
		for (Plugin plugin : plugins) {
			if (plugin.getGroupId().equals(PLUGIN_GROUPID) && plugin.getArtifactId().equals(PLUGIN_ARTIFACTID)) {
				Mixins mixins = loadConfiguration(plugin.getConfiguration());
				//First start with the base level and then add the inherited mixins
				for(Mixin mixin: mixins.getMixins()){
					if(!mixinMap.containsKey(mixin.getKey())){
						logger.debug(String.format("Adding mixin: %s to cache", mixin.getKey()));

						mixinModelCache.getModel(mixin, mavenProject);
						mixinMap.put(mixin.getKey(), mixin);
						mixinList.add(mixin);
					}
				}
				for(Mixin mixin: mixins.getMixins()){
					if(mixin.isRecurse()){
						Model mixinModel = mixinModelCache.getModel(mixin, mavenProject);
						fillMixins(mixinList, mixinMap, mixinModel);
					}
				}
			}
		}
	}

    private DefaultProfileActivationContext getProfileActivationContext() {
        DefaultProfileActivationContext context = new DefaultProfileActivationContext();
        List<String> activeProfileIds = new ArrayList<>();
        List<String> inactiveProfileIds = new ArrayList<>();
        for(Profile profile: mavenProject.getActiveProfiles()){
        	activeProfileIds.add(profile.getId());
        }
        activeProfileIds.addAll(modelBuildingRequest.getActiveProfileIds());
        for(Profile profile: mavenProject.getModel().getProfiles()){
        	if(profile.getActivation() != null && !activeProfileIds.contains(profile.getId())){
        		inactiveProfileIds.add(profile.getId());
        	}
        }
        inactiveProfileIds.addAll(modelBuildingRequest.getInactiveProfileIds());
        context.setActiveProfileIds( activeProfileIds);
        context.setInactiveProfileIds( inactiveProfileIds );
        context.setSystemProperties( mavenSession.getSystemProperties() );
        context.setUserProperties( mavenSession.getUserProperties() );
        context.setProjectDirectory( mavenProject.getBasedir() );
        return context;
    }
    
	private Mixins loadConfiguration(Object configuration) throws MavenExecutionException {
		Mixins mixins = new Mixins();
		BeanConfigurationRequest request = new DefaultBeanConfigurationRequest();
		request.setBean(mixins);
		request.setConfiguration(configuration, "mixins");
		try {
			beanConfigurator.configureBean(request);
			return mixins;
		} catch (BeanConfigurationException e) {
			throw new MavenExecutionException("Cannot load mixins configuration: " + e.getMessage(), e); 
		}
	}

}
