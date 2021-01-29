/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.nativex.support;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.MissingResourceException;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.Stack;
import java.util.StringTokenizer;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.nativex.domain.init.InitializationDescriptor;
import org.springframework.nativex.domain.reflect.Flag;
import org.springframework.nativex.domain.reflect.MethodDescriptor;
import org.springframework.nativex.domain.reflect.ReflectionDescriptor;
import org.springframework.nativex.domain.resources.ResourcesDescriptor;
import org.springframework.nativex.domain.resources.ResourcesJsonMarshaller;
import org.springframework.nativex.extension.ComponentProcessor;
import org.springframework.nativex.extension.NativeImageContext;
import org.springframework.nativex.extension.SpringFactoriesProcessor;
import org.springframework.nativex.type.AccessBits;
import org.springframework.nativex.type.AccessDescriptor;
import org.springframework.nativex.type.HintApplication;
import org.springframework.nativex.type.HintDeclaration;
import org.springframework.nativex.type.Method;
import org.springframework.nativex.type.MissingTypeException;
import org.springframework.nativex.type.ProxyDescriptor;
import org.springframework.nativex.type.Type;
import org.springframework.nativex.type.TypeSystem;


public class ResourcesHandler extends Handler {
	
	private final static String enableAutoconfigurationKey = "org.springframework.boot.autoconfigure.EnableAutoConfiguration";

	private final static String propertySourceLoaderKey = "org.springframework.boot.env.PropertySourceLoader";

	private final static String managementContextConfigurationKey = "org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration";

	private ReflectionHandler reflectionHandler;

	private DynamicProxiesHandler dynamicProxiesHandler;

	private InitializationHandler initializationHandler;

	public ResourcesHandler(ConfigurationCollector collector, ReflectionHandler reflectionHandler, DynamicProxiesHandler dynamicProxiesHandler, InitializationHandler initializationHandler) {
		super(collector);
		this.reflectionHandler = reflectionHandler;
		this.dynamicProxiesHandler = dynamicProxiesHandler;
		this.initializationHandler = initializationHandler;
	}

	/**
	 * Read in the static resource list from inside this project. Common resources for all Spring applications.
	 * @return a ResourcesDescriptor describing the resources from the file
	 */
	public ResourcesDescriptor readStaticResourcesConfiguration() {
		try {
			InputStream s = this.getClass().getResourceAsStream("/resources.json");
			return ResourcesJsonMarshaller.read(s);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Callback from native-image. Determine resources related to Spring applications that need to be added to the image.
	 * @param access provides API access to native image construction information
	 */
	public void register() {
		ResourcesDescriptor rd = readStaticResourcesConfiguration();
		if (ConfigOptions.isFunctionalMode() ||
			ConfigOptions.isAnnotationMode() ||
			ConfigOptions.isAgentMode()) {
			SpringFeature.log("Registering statically declared resources - #" + rd.getPatterns().size() + " patterns");
			registerPatterns(rd);
			registerResourceBundles(rd);
		}
		if (ConfigOptions.isAnnotationMode() ||
			ConfigOptions.isAgentMode() ||
			ConfigOptions.isSpringInitActive()) {
			processSpringFactories();
		}
		if (!ConfigOptions.isInitMode()) {
			handleConstantHints();
		}
		handleConstantInitializationHints();
		if (ConfigOptions.isAnnotationMode() ||
			ConfigOptions.isAgentMode() ||
			ConfigOptions.isFunctionalMode()) {
			handleSpringComponents();
		}
	}

	private void registerPatterns(ResourcesDescriptor rd) {
		for (String pattern : rd.getPatterns()) {
			if (pattern.equals("META-INF/spring.factories")) {
				continue; // leave to special handling which may trim these files...
			}
			collector.addResource(pattern, false);
		}
	}

	private void registerResourceBundles(ResourcesDescriptor rd) {
		System.out.println("Registering resources - #" + rd.getBundles().size() + " bundles");
		for (String bundle : rd.getBundles()) {
			try {
				ResourceBundle.getBundle(bundle);
				collector.addResource(bundle, true);
			} catch (MissingResourceException e) {
				//bundle not available. don't load it
			}
		}
	}

	/**
	 * Some types need reflective access in every Spring Boot application. When hints are scanned
	 * these 'constants' are registered against the java.lang.Object key. Because they won't have been 
	 * registered in regular analysis, here we explicitly register those. Initialization hints are handled
	 * separately.
	 */
	private void handleConstantHints() {
		List<HintDeclaration> constantHints = ts.findActiveDefaultHints();
		SpringFeature.log("> Registering fixed hints: " + constantHints);
		for (HintDeclaration ch : constantHints) {
			if (!isHintValidForCurrentMode(ch)) {
				continue;
			}
			Map<String, AccessDescriptor> dependantTypes = ch.getDependantTypes();
			for (Map.Entry<String, AccessDescriptor> dependantType : dependantTypes.entrySet()) {
				String typename = dependantType.getKey();
				AccessDescriptor ad = dependantType.getValue();
				SpringFeature.log("  fixed type registered "+typename+" with "+ad);
				List<org.springframework.nativex.type.MethodDescriptor> mds = ad.getMethodDescriptors();
				Flag[] accessFlags = AccessBits.getFlags(ad.getAccessBits()); 
				if (mds!=null && mds.size()!=0 && AccessBits.isSet(ad.getAccessBits(),AccessBits.DECLARED_METHODS | AccessBits.PUBLIC_METHODS)) {
					SpringFeature.log("  type has #"+mds.size()+" members specified, removing typewide method access flags");
					accessFlags = filterFlags(accessFlags, Flag.allDeclaredMethods, Flag.allPublicMethods);
				}
				reflectionHandler.addAccess(typename, MethodDescriptor.toStringArray(mds), null, true, accessFlags);
			}
			List<ProxyDescriptor> proxyDescriptors = ch.getProxyDescriptors();
			for (ProxyDescriptor pd: proxyDescriptors) {
				SpringFeature.log("Registering proxy descriptor: "+pd);
				dynamicProxiesHandler.addProxy(pd);
			}
			List<org.springframework.nativex.type.ResourcesDescriptor> resourcesDescriptors = ch.getResourcesDescriptors();
			for (org.springframework.nativex.type.ResourcesDescriptor rd: resourcesDescriptors) {
				SpringFeature.log("Registering resource descriptor: "+rd);
				registerResourcesDescriptor(rd);
			}
		}
		SpringFeature.log("< Registering fixed hints");
	}
	
	private void handleConstantInitializationHints() {
		List<HintDeclaration> constantHints = ts.findHints("java.lang.Object");
		SpringFeature.log("Registering fixed initialization entries: ");
		for (HintDeclaration ch : constantHints) {
			List<InitializationDescriptor> ids = ch.getInitializationDescriptors();
			for (InitializationDescriptor id: ids) {
				SpringFeature.log(" registering initialization descriptor: "+id);
				initializationHandler.registerInitializationDescriptor(id);
			}
		}
	}
	
	public void registerResourcesDescriptor(org.springframework.nativex.type.ResourcesDescriptor rd) {
		String[] patterns = rd.getPatterns();
		for (String pattern: patterns) {
			collector.addResource(pattern,rd.isBundle());
			
		}	
	}

	/**
	 * Discover existing spring.components or synthesize one if none are found. If not running
	 * in hybrid mode then process the spring.components entries.
	 */
	public void handleSpringComponents() {
		NativeImageContext context = new NativeImageContextImpl();
//		Enumeration<URL> springComponents = fetchResources("META-INF/spring.components");
		Collection<byte[]> springComponents = ts.getResources("META-INF/spring.components");
		List<String> alreadyProcessed = new ArrayList<>();
		if (springComponents.size()!=0) {
//		if (springComponents.hasMoreElements()) {
			log("Processing existing META-INF/spring.components files...");
			for (byte[] springComponentsFile: springComponents) {
//			while (springComponents.hasMoreElements()) {
//				URL springFactory = springComponents.nextElement();
				Properties p = new Properties();
				try (ByteArrayInputStream bais = new ByteArrayInputStream(springComponentsFile)) {
					p.load(bais);
				} catch (IOException e) {
					throw new IllegalStateException("Unable to load spring.factories", e);
				}
//				loadSpringFactoryFile(springFactory, p);
				if (ConfigOptions.isAgentMode()) {
					processSpringComponentsAgent(p, context);
				} else if (ConfigOptions.isFunctionalMode()) {
					processSpringComponentsFunc(p, context, alreadyProcessed);
				} else {
					System.out.println(">> ResourceHandler#handleSpringComponents()");
					processSpringComponents(p, context, alreadyProcessed);
				}
			}
		} else {
			log("Found no META-INF/spring.components -> synthesizing one...");
			Properties p = synthesizeSpringComponents();
			if (ConfigOptions.isAgentMode()) {
				processSpringComponentsAgent(p, context);
			} else if (ConfigOptions.isFunctionalMode()) {
				processSpringComponentsFunc(p, context, alreadyProcessed);
			} else {
				processSpringComponents(p, context, alreadyProcessed);
			}
		}
	}

	private Properties synthesizeSpringComponents() {
		Properties p = new Properties();
		List<Entry<Type, List<Type>>> components = ts.scanForSpringComponents();
		List<Entry<Type, List<Type>>> filteredComponents = filterOutNestedTypes(components);
		for (Entry<Type, List<Type>> filteredComponent : filteredComponents) {
			String k = filteredComponent.getKey().getDottedName();
			p.put(k, filteredComponent.getValue().stream().map(t -> t.getDottedName())
					.collect(Collectors.joining(",")));
		}
		System.out.println("Computed spring.components is ");
		System.out.println("vvv");
		for (Object k : p.keySet()) {
			System.out.println(k + "=" + p.getProperty((String) k));
		}
		System.out.println("^^^");
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			p.store(baos, "");
			baos.close();
			byte[] bs = baos.toByteArray();
			ByteArrayInputStream bais = new ByteArrayInputStream(bs);
			collector.registerResource("META-INF/spring.components", bais);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return p;
	}
	
	// TODO [0.9.0] rationalize duplicate processSpringComponentXXXX methods
	private void processSpringComponentsFunc(Properties p, NativeImageContext context,List<String> alreadyProcessed) {
		Enumeration<Object> keys = p.keys();
		while (keys.hasMoreElements()) {
			String key = (String)keys.nextElement();
			String valueString = (String)p.get(key);
			if (valueString.equals("package-info")) {
				continue;
			}
			Type keyType = ts.resolveDotted(key);
			if (keyType.isAtConfiguration()) {
				checkAndRegisterConfigurationType(key,ReachedBy.FromSpringComponent);
			}
			// TODO [0.9.0] do we need to differentiate between 'functional' and 'functional with spring-init'
			if (ConfigOptions.isSpringInitActive()) {
				List<String> values = Arrays.asList(valueString.split(","));
				for (ComponentProcessor componentProcessor: ts.getComponentProcessors()) {
					if (componentProcessor.handle(context, key, values)) {
						componentProcessor.process(context, key, values);
					}
				}	
			}
		}
	}
	
	private void processSpringComponentsAgent(Properties p, NativeImageContext context) {
		Enumeration<Object> keys = p.keys();
		while (keys.hasMoreElements()) {
			String key = (String)keys.nextElement();
			String valueString = (String)p.get(key);
			if (valueString.equals("package-info")) {
				continue;
			}
			Type keyType = ts.resolveDotted(key);
			// The context start/stop test may not exercise the @SpringBootApplication class
			if (keyType.isAtSpringBootApplication()) {
				System.out.println("hybrid: adding access to "+keyType+" since @SpringBootApplication");
				reflectionHandler.addAccess(key,  Flag.allDeclaredMethods, Flag.allDeclaredFields, Flag.allDeclaredConstructors);
//				resourcesRegistry.addResources(key.replace(".", "/")+".class");
				collector.addResource(key.replace(".", "/")+".class", false);
			}
			if (keyType.isAtController()) {
				System.out.println("hybrid: Processing controller "+key);
				List<Method> mappings = keyType.getMethods(m -> m.isAtMapping());
				// Example:
				// @GetMapping("/greeting")
				// public String greeting( @RequestParam(name = "name", required = false, defaultValue = "World") String name, Model model) {
				for (Method mapping: mappings) {
					for (int pi=0;pi<mapping.getParameterCount();pi++) {
						List<Type> parameterAnnotationTypes = mapping.getParameterAnnotationTypes(pi);
						for (Type parameterAnnotationType: parameterAnnotationTypes) {
							if (parameterAnnotationType.hasAliasForMarkedMembers()) {
								List<String> interfaces = new ArrayList<>();
								interfaces.add(parameterAnnotationType.getDottedName());
								interfaces.add("org.springframework.core.annotation.SynthesizedAnnotation");
								System.out.println("Adding dynamic proxy for "+interfaces);
								dynamicProxiesHandler.addProxy(interfaces);
							}
						}
					}
				}
			}
		}
	}

	/**
	 * Process a spring components properties object. The data within will look like:
	 * <pre><code>
	 * app.main.SampleApplication=org.springframework.stereotype.Component
	 * app.main.model.Foo=javax.persistence.Entity,something.Else
	 * app.main.model.FooRepository=org.springframework.data.repository.Repository
	 * </code></pre>
	 * @param p the properties object containing spring components
	 */
	private void processSpringComponents(Properties p, NativeImageContext context, List<String> alreadyProcessed) {
		int registeredComponents = 0;
		RequestedConfigurationManager requestor = new RequestedConfigurationManager();
		for (Entry<Object, Object> entry : p.entrySet()) {
			boolean processedOK = processSpringComponent((String)entry.getKey(), (String)entry.getValue(), context, requestor, alreadyProcessed);
			if (processedOK) {
				registeredComponents++;
			}
		}
		registerAllRequested(requestor);
		ts.getComponentProcessors().forEach(ComponentProcessor::printSummary);
		SpringFeature.log("Registered " + registeredComponents + " entries");
	}
	
	private boolean processSpringComponent(String componentTypename, String classifiers, NativeImageContext context, RequestedConfigurationManager requestor, List<String> alreadyProcessed) {
		ProcessingContext pc = ProcessingContext.of(componentTypename, ReachedBy.FromSpringComponent);
		List<ComponentProcessor> componentProcessors = ts.getComponentProcessors();
		boolean isComponent = false;
		if (classifiers.equals("package-info")) {
			return false;
		}
		if (alreadyProcessed.contains(componentTypename+":"+classifiers)) {
			return false;
		}
		alreadyProcessed.add(componentTypename+":"+classifiers);
		Type kType = ts.resolveDotted(componentTypename);
		SpringFeature.log("Registering Spring Component: " + componentTypename);

		// Ensure if usage of @Component is meta-usage, the annotations that are meta-annotated are
		// exposed
		Entry<Type, List<Type>> metaAnnotated = kType.getMetaComponentTaggedAnnotations();
		if (metaAnnotated != null) {
			for (Type t: metaAnnotated.getValue()) {
				String name = t.getDottedName();
				reflectionHandler.addAccess(name, Flag.allDeclaredMethods);
				collector.addResource(name.replace(".", "/")+".class", false);
//				resourcesRegistry.addResources(name.replace(".", "/")+".class");
			}
		}

		if (kType.isAtConfiguration()) {
			// Treat user configuration (from spring.components) the same as configuration
			// discovered via spring.factories
			checkAndRegisterConfigurationType(componentTypename,ReachedBy.FromSpringComponent);
		} else {
			try {
				// TODO assess which kinds of thing requiring what kind of access - here we see
				// an Entity might require field reflective access where others don't
				// I think as a component may have autowired fields (and an entity may have
				// interesting fields) - you kind of always need to expose fields
				// There is a type in vanilla-orm called Bootstrap that shows this need
				reflectionHandler.addAccess(componentTypename, Flag.allDeclaredConstructors, Flag.allDeclaredMethods,
					Flag.allDeclaredClasses, Flag.allDeclaredFields);
//				resourcesRegistry.addResources(componentTypename.replace(".", "/") + ".class");
				collector.addResource(componentTypename.replace(".", "/")+".class", false);
				// Register nested types of the component
				for (Type t : kType.getNestedTypes()) {
					reflectionHandler.addAccess(t.getDottedName(), Flag.allDeclaredConstructors, Flag.allDeclaredMethods,
							Flag.allDeclaredClasses);
//					resourcesRegistry.addResources(t.getName() + ".class");
					collector.addResource(t.getName()+".class", false);
				}
				registerHierarchy(pc, kType, requestor);
			} catch (Throwable t) {
				t.printStackTrace();
			}
		}
		/*
		if (kType != null && kType.isAtRepository()) { // See JpaVisitRepositoryImpl in petclinic sample
		    // TODO [0.9.0] is this all handled by SpringDataComponentProcessor now?
			processRepository2(kType);
		}
		*/
		if (kType != null && kType.isAtResponseBody()) {
			// TODO [0.9.0] move into WebComponentProcessor?
			processResponseBodyComponent(kType);
		}
		List<String> values = new ArrayList<>();
		StringTokenizer st = new StringTokenizer(classifiers, ",");
		// org.springframework.samples.petclinic.visit.JpaVisitRepositoryImpl=org.springframework.stereotype.Component,javax.transaction.Transactional
		while (st.hasMoreElements()) {
			String tt = st.nextToken();
			values.add(tt);
			if (tt.equals("org.springframework.stereotype.Component")) {
				isComponent = true;
			}
			try {
				Type baseType = ts.resolveDotted(tt);

				// reflectionHandler.addAccess(tt,Flag.allDeclaredConstructors,
				// Flag.allDeclaredMethods, Flag.allDeclaredClasses);
				// reflectionHandler.addAccess(tt,Flag.allPublicConstructors,
				// Flag.allPublicMethods, Flag.allDeclaredClasses);
				reflectionHandler.addAccess(tt, Flag.allDeclaredMethods);
//				resourcesRegistry.addResources(tt.replace(".", "/") + ".class");
				collector.addResource(tt.replace(".", "/")+".class", false);
				// Register nested types of the component
				for (Type t : baseType.getNestedTypes()) {
					String n = t.getName().replace("/", ".");
					reflectionHandler.addAccess(n, Flag.allDeclaredMethods);
//					reflectionHandler.addAccess(n, Flag.allDeclaredConstructors, Flag.allDeclaredMethods, Flag.allDeclaredClasses);
//					resourcesRegistry.addResources(t.getName() + ".class");
					collector.addResource(t.getName() + ".class", false);
				}
				registerHierarchy(pc, baseType, requestor);
			} catch (Throwable t) {
				t.printStackTrace();
				System.out.println("Problems with value " + tt);
			}
		}
		if (isComponent && ConfigOptions.isVerifierOn()) {
			kType.verifyComponent();
		}
		for (Type type : kType.getNestedTypes()) {
			if (type.isComponent()) {
				// TODO do we need to fill in the classifiers list here (second param) correctly?
				// (We could do it, inferring like we infer spring.components in general)
				processSpringComponent(type.getDottedName(),"",context,requestor,alreadyProcessed);
			}
		}
		for (ComponentProcessor componentProcessor: componentProcessors) {
			if (componentProcessor.handle(context, componentTypename, values)) {
				System.out.println(">> About to call process for " + componentProcessor.toString());
				componentProcessor.process(context, componentTypename, values);
			}
		}	
		return true;
	}

	/**
	 * This is the type passed to the 'plugins' that process spring components or spring factories entries.
	 */
	class NativeImageContextImpl implements NativeImageContext {

		private final HashMap<String, Flag[]> reflectiveFlags = new LinkedHashMap<>();

		@Override
		public boolean addProxy(List<String> interfaces) {
			System.out.println(">> NativeImageContextImpl#addProxy(List<String>)");
			dynamicProxiesHandler.addProxy(interfaces);
			return true;
		}

		@Override
		public boolean addProxy(String... interfaces) {
			System.out.println(">> NativeImageContextImpl#addProxy(String...)");
			if (interfaces != null) {
				dynamicProxiesHandler.addProxy(Arrays.asList(interfaces));
			}
			return true;
		}

		@Override
		public TypeSystem getTypeSystem() {
			return ts;
		}

		@Override
		public void addReflectiveAccess(String key, Flag... flags) {
			reflectionHandler.addAccess(key, flags);
			// TODO: is there a way to ask the ReflectionRegistry? If not may keep track of flag changes.
			reflectiveFlags.put(key, flags);
		}

		@Override
		public boolean hasReflectionConfigFor(String key) {
			return reflectiveFlags.containsKey(key);
		}

		@Override
		public void initializeAtBuildTime(Type type) {
			collector.initializeAtBuildTime(type.getDottedName());
		}

		@Override
		public Set<String> addReflectiveAccessHierarchy(String typename, int accessBits) {
			Type type = ts.resolveDotted(typename, true);
			Set<String> added = new TreeSet<>();
			registerHierarchy(type, added, accessBits);
			return added;
		}
		
		private void registerHierarchy(Type type, Set<String> visited, int accessBits) {
			String typename = type.getDottedName();
			if (visited.add(typename)) {
				addReflectiveAccess(typename, AccessBits.getFlags(accessBits));
				Set<String> relatedTypes = type.getTypesInSignature();
				for (String relatedType: relatedTypes) {
					Type t = ts.resolveSlashed(relatedType, true);
					if (t!=null) {
						registerHierarchy(t, visited, accessBits);
					}
				}
			}
		}

		@Override
		public void log(String message) {
			SpringFeature.log(message);
		}

		@Override
		public void addResourceBundle(String bundleName) {
			registerResourceBundles(ResourcesDescriptor.ofBundle(bundleName));
		}
		
	}
	
	private void processResponseBodyComponent(Type t) {
	  // If a controller is marked up @ResponseBody (possibly via @RestController), need to register reflective access to
	  // the return types of the methods marked @Mapping (meta marked) 
	  Collection<Type> returnTypes = t.collectAtMappingMarkedReturnTypes();
	  SpringFeature.log("Found these return types from Mapped methods in "+t.getName()+" > "+returnTypes);
	  for (Type returnType: returnTypes ) {
		  if (returnType==null) {
			  continue;
		  }
		  reflectionHandler.addAccess(returnType.getDottedName(), Flag.allDeclaredMethods, Flag.allDeclaredConstructors,Flag.allDeclaredFields);
	  }
	}

	// Code from petclinic that ends us up in here:
	// public interface VisitRepository { ... }
	// @org.springframework.stereotype.Repository @Transactional public class JpaVisitRepositoryImpl implements VisitRepository { ... }
	// Need proxy: [org.springframework.samples.petclinic.visit.VisitRepository,org.springframework.aop.SpringProxy,
	//              org.springframework.aop.framework.Advised,org.springframework.core.DecoratingProxy] 
	// And entering here with r = JpaVisitRepositoryImpl
	private void processRepository2(Type r) {
		SpringFeature.log("Processing @oss.Repository annotated "+r.getDottedName());
		List<String> repositoryInterfaces = new ArrayList<>();
		for (String s: r.getInterfacesStrings()) {
			repositoryInterfaces.add(s.replace("/", "."));
		}
		repositoryInterfaces.add("org.springframework.aop.SpringProxy");
		repositoryInterfaces.add("org.springframework.aop.framework.Advised");
		repositoryInterfaces.add("org.springframework.core.DecoratingProxy");
		dynamicProxiesHandler.addProxy(repositoryInterfaces);
	}

	/**
	 * Walk a type hierarchy and register them all for reflective access.
	 * @param pc 
	 * 
	 * @param type the type whose hierarchy to register
	 * @param typesToMakeAccessible if non null required accesses are collected here rather than recorded directly on the runtime
	 */
	public void registerHierarchy(ProcessingContext pc, Type type, RequestedConfigurationManager typesToMakeAccessible) {
		AccessBits accessRequired = AccessBits.forValue(Type.inferAccessRequired(type));
		boolean isConfiguration = type.isAtConfiguration();
		if (!isConfiguration) {
			// Double check are we here because we are a parent of some configuration being processed
			// For example: 
			// Analyzing org.springframework.web.reactive.config.WebFluxConfigurationSupport 
			//   reached by 
			// [[Ctx:org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration-FromSpringFactoriesKey], 
			// [Ctx:org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration$EnableWebFluxConfiguration-NestedReference], 
			// [Ctx:org.springframework.web.reactive.config.DelegatingWebFluxConfiguration-HierarchyProcessing], 
			// [Ctx:org.springframework.web.reactive.config.WebFluxConfigurationSupport-HierarchyProcessing]]
			// TODO [0.9.0] tidyup
			String s2 = pc.getHierarchyProcessingTopMostTypename();
			TypeSystem typeSystem = type.getTypeSystem();
			Type resolve = typeSystem.resolveDotted(s2,true);
			isConfiguration = resolve.isAtConfiguration();
		}
		if (isConfiguration && ConfigOptions.isFunctionalMode()) {
			SpringFeature.log("Not registering hierarchy of "+type.getDottedName()+" because functional mode and the type is configuration");
			return;
		}
		registerHierarchyHelper(type, new HashSet<>(), typesToMakeAccessible, accessRequired, isConfiguration);
	}
	
	private void registerHierarchyHelper(Type type, Set<Type> visited, RequestedConfigurationManager typesToMakeAccessible,
			AccessBits inferredRequiredAccess, boolean rootTypeWasConfiguration) {
		if (typesToMakeAccessible == null) {
			throw new IllegalStateException();
		}
		if (type == null || !visited.add(type)) {
			return;
		}
		if (type.isCondition()) {
			if (type.hasOnlySimpleConstructor()) {
				typesToMakeAccessible.requestTypeAccess(type.getDottedName(),inferredRequiredAccess.getValue());
			} else {
				typesToMakeAccessible.requestTypeAccess(type.getDottedName(),inferredRequiredAccess.getValue());
			}
		} else {
			// TODO we can do better here, why can we not use the inferredRequiredAccess -
			// it looks like we aren't adding RESOURCE to something when inferring.
			typesToMakeAccessible.requestTypeAccess(type.getDottedName(),
					AccessBits.DECLARED_CONSTRUCTORS|
					AccessBits.RESOURCE|(rootTypeWasConfiguration?AccessBits.DECLARED_METHODS:AccessBits.PUBLIC_METHODS));
//					inferredRequiredAccess.getValue());
			// reflectionHandler.addAccess(configNameDotted, Flag.allDeclaredConstructors,
			// Flag.allDeclaredMethods);
		}
		
		if (rootTypeWasConfiguration && !type.isAtConfiguration()) {
			// Processing a superclass of a configuration (so may contain @Bean methods)
		}
		// Rather than just looking at superclass and interfaces, this will dig into everything including
		// parameterized type references so nothing is missed
//		if (type.getSuperclass()!=null) {
//			System.out.println("RH>SC "+type.getSuperclass());
//		registerHierarchyHelper(type.getSuperclass(),visited, typesToMakeAccessible,inferredRequiredAccess);
//		}
//		Type[] intfaces = type.getInterfaces();
//		for (Type intface: intfaces) {
//			System.out.println("RH>IF "+intface);
//			registerHierarchyHelper(intface,visited, typesToMakeAccessible,inferredRequiredAccess);
//		}
/*		
		List<String> supers = new ArrayList<>();
		if (type.getSuperclass()!=null) {
			supers.add(type.getSuperclass().getDottedName());
		}
		Type[] intfaces = type.getInterfaces();
		for (Type intface: intfaces) {
			supers.add(intface.getDottedName());
		}
		List<String> lst = new ArrayList<>();
*/	
		
		Type superclass = type.getSuperclass();
		registerHierarchyHelper(superclass, visited, typesToMakeAccessible, inferredRequiredAccess, true);
		
		Set<String> relatedTypes = type.getTypesInSignature();
		for (String relatedType: relatedTypes) {
			Type t = ts.resolveSlashed(relatedType,true);
			if (t!=null) {
//				lst.add(t.getDottedName());
				registerHierarchyHelper(t, visited, typesToMakeAccessible, inferredRequiredAccess, false);
			}
		}
//		lst.removeAll(supers);
//		if (lst.size()!=0) {
//		System.out.println("MISSED THESE ("+type.getDottedName()+"): "+lst);
//		}
	}

	/**
	 * Find all META-INF/spring.factories - for any configurations listed in each,
	 * check if those configurations use ConditionalOnClass. If the classes listed
	 * in ConditionalOnClass can't be found, discard the configuration from
	 * spring.factories. Register either the unchanged or modified spring.factories
	 * files with the system.
	 */
	public void processSpringFactories() {
		log("Processing META-INF/spring.factories files...");
		for (byte[] springFactory: ts.getResources("META-INF/spring.factories")) {
			Properties p = new Properties();
			try (ByteArrayInputStream bais = new ByteArrayInputStream(springFactory)) {
				p.load(bais);
			} catch (IOException e) {
				throw new IllegalStateException("Unable to load bytes from spring factory file", e);
			}
//			loadSpringFactoryFile(springFactory, p);
			processSpringFactory(ts, p);
		}
//		Enumeration<URL> springFactories = fetchResources("META-INF/spring.factories");
//		while (springFactories.hasMoreElements()) {
//			URL springFactory = springFactories.nextElement();
//			processSpringFactory(ts, springFactory);
//		}
	}

	private List<Entry<Type, List<Type>>> filterOutNestedTypes(List<Entry<Type, List<Type>>> springComponents) {
		List<Entry<Type, List<Type>>> filtered = new ArrayList<>();
		List<Entry<Type, List<Type>>> subtypesToRemove = new ArrayList<>();
		for (Entry<Type, List<Type>> a : springComponents) {
			String type = a.getKey().getDottedName();
			subtypesToRemove.addAll(springComponents.stream()
					.filter(e -> e.getKey().getDottedName().startsWith(type + "$")).collect(Collectors.toList()));
		}
		filtered.addAll(springComponents);
		filtered.removeAll(subtypesToRemove);
		return filtered;
	}

	/**
	 * A key in a spring.factories file has a value that is a list of types. These
	 * will be accessed at runtime through an interface but must be reflectively
	 * instantiated. Hence reflective access to constructors but not to methods.
	 */
	private void registerTypeReferencedBySpringFactoriesKey(String s) {
		try {
			Type t = ts.resolveDotted(s, true);
			if (t != null) {
				// This 'name' may not be the same as 's' if 's' referred to an inner type -
				// 'name' will include the right '$' characters.
				String name = t.getDottedName();
				if (t.hasOnlySimpleConstructor()) {
					reflectionHandler.addAccess(name, new String[][] { { "<init>" } },null, false);
				} else {
					reflectionHandler.addAccess(name, Flag.allDeclaredConstructors);
				}
			}
		} catch (NoClassDefFoundError ncdfe) {
			System.out.println(
					"spring.factories processing, problem adding access for key " + s + ": " + ncdfe.getMessage());
		}
	}

	private void processSpringFactory(TypeSystem ts, URL springFactory) {
		SpringFeature.log("processing spring factory file "+springFactory);
		Properties p = new Properties();
		loadSpringFactoryFile(springFactory, p);
		processSpringFactory(ts, p);
	}
		
		
	private void processSpringFactory(TypeSystem ts, Properties p) {
		List<SpringFactoriesProcessor> springFactoriesProcessors = ts.getSpringFactoryProcessors();
		List<String> forRemoval = new ArrayList<>();
		int excludedAutoConfigCount = 0;
		Enumeration<Object> factoryKeys = p.keys();
		boolean modified = false;
		
		if (!ConfigOptions.isAgentMode()) {
			Properties filteredProperties = new Properties();
			for (Map.Entry<Object, Object> factoriesEntry : p.entrySet()) {
				String key = (String) factoriesEntry.getKey();
				String valueString = (String) factoriesEntry.getValue();
				List<String> values = new ArrayList<>();
				for (String value : valueString.split(",")) {
					values.add(value);
				}
				if (ConfigOptions.shouldRemoveUnusedAutoconfig()) {
				for (SpringFactoriesProcessor springFactoriesProcessor : springFactoriesProcessors) {
					int len = values.size();
					if (springFactoriesProcessor.filter(key, values)) {
						SpringFeature.log("Spring factory filtered by "+springFactoriesProcessor.getClass().getName()+" removing "+(len-values.size())+" entries");
						modified = true;
					}
				}
				}
				if (modified) {
					filteredProperties.put(key, String.join(",", values));
				} else {
					filteredProperties.put(key, valueString);
				}
			}
			p = filteredProperties;
		}

		factoryKeys = p.keys();
		// Handle all keys other than EnableAutoConfiguration and PropertySourceLoader
		if (!ConfigOptions.isAgentMode()) {
			while (factoryKeys.hasMoreElements()) {
				String k = (String) factoryKeys.nextElement();
				SpringFeature.log("Adding all the classes for this key: " + k);
				if (!k.equals(enableAutoconfigurationKey) 
						&& !k.equals(propertySourceLoaderKey)
						&& !k.equals(managementContextConfigurationKey) 
						) {
					if (ts.shouldBeProcessed(k)) {
						for (String v : p.getProperty(k).split(",")) {
							registerTypeReferencedBySpringFactoriesKey(v);
						}
					} else {
						SpringFeature
								.log("Skipping processing spring.factories key " + k + " due to missing guard types");
					}
				}
			}
		}

		if (!ConfigOptions.isAgentMode()) {
			// Handle PropertySourceLoader
			String propertySourceLoaderValues = (String) p.get(propertySourceLoaderKey);
			if (propertySourceLoaderValues != null) {
				List<String> propertySourceLoaders = new ArrayList<>();
				for (String s : propertySourceLoaderValues.split(",")) {
					if (!s.equals("org.springframework.boot.env.YamlPropertySourceLoader")
							|| !ConfigOptions.shouldRemoveYamlSupport()) {
						registerTypeReferencedBySpringFactoriesKey(s);
						propertySourceLoaders.add(s);
					} else {
						forRemoval.add(s);
					}
				}
				System.out.println("Processing spring.factories - PropertySourceLoader lists #"
						+ propertySourceLoaders.size() + " property source loaders");
				SpringFeature.log("These property source loaders are remaining in the PropertySourceLoader key value:");
				for (int c = 0; c < propertySourceLoaders.size(); c++) {
					SpringFeature.log((c + 1) + ") " + propertySourceLoaders.get(c));
				}
				p.put(propertySourceLoaderKey, String.join(",", propertySourceLoaders));
			}
		}

		modified = processConfigurationsWithKey(p, managementContextConfigurationKey) || modified;
		
		// TODO refactor this chunk to call processConfigurationsWithKey()
		// Handle EnableAutoConfiguration
		String enableAutoConfigurationValues = (String) p.get(enableAutoconfigurationKey);
		if (enableAutoConfigurationValues != null) {
			List<String> configurations = new ArrayList<>();
			for (String s : enableAutoConfigurationValues.split(",")) {
				configurations.add(s);
			}
			System.out.println("Processing spring.factories - EnableAutoConfiguration lists #" + configurations.size()
					+ " configurations");
			for (String config : configurations) {
				if (!checkAndRegisterConfigurationType(config,ReachedBy.FromSpringFactoriesKey)) {
					if (ConfigOptions.shouldRemoveUnusedAutoconfig()) {
						excludedAutoConfigCount++;
						SpringFeature.log("Excluding auto-configuration " + config);
						forRemoval.add(config);
					}
				}
			}
			if (ConfigOptions.shouldRemoveUnusedAutoconfig()) {
				System.out.println(
						"Excluding " + excludedAutoConfigCount + " auto-configurations from spring.factories file");
				configurations.removeAll(forRemoval);
				p.put(enableAutoconfigurationKey, String.join(",", configurations));
				SpringFeature.log("These configurations are remaining in the EnableAutoConfiguration key value:");
				for (int c = 0; c < configurations.size(); c++) {
					SpringFeature.log((c + 1) + ") " + configurations.get(c));
				}
			}
		}

		if (forRemoval.size() > 0) {
			String existingRC = ts.findAnyResourceConfigIncludingSpringFactoriesPattern();
			if (existingRC != null) {
				System.out.println("WARNING: unable to trim META-INF/spring.factories (for example to disable unused auto configurations)"+
					" because an existing resource-config is directly including it: "+existingRC);
				return;
			}
		}	

		// Filter spring.factories if necessary
		try {
			if (forRemoval.size() == 0 && !modified) {
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				p.store(baos, null);
				byte[] bs = baos.toByteArray();
				collector.registerResource("META-INF/spring.factories", new ByteArrayInputStream(bs));
//				Resources.registerResource("META-INF/spring.factories", springFactory.openStream());
			} else {
				SpringFeature.log("  removed " + forRemoval.size() + " classes");
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				p.store(baos, "");
				baos.close();
				byte[] bs = baos.toByteArray();
				SpringFeature.log("The new spring.factories is: vvvvvvvvv");
				SpringFeature.log(new String(bs));
				SpringFeature.log("^^^^^^^^");
				ByteArrayInputStream bais = new ByteArrayInputStream(bs);
				collector.registerResource("META-INF/spring.factories", bais);
//				Resources.registerResource("META-INF/spring.factories", bais);
			}
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Find the configurations referred to by the specified key in the specified properties object.
	 * Process each configuration and if it fails conditional checks (e.g. @ConditionalOnClass)
	 * then it is considered inactive and removed. The key is rewritten with a new list of configurations
	 * with inactive ones removed.
	 * @param p the properties object
	 * @param configurationsKey the key into the properties object whose value is a configurations list
	 * 
	 * @return true if inactive configurations were discovered and removed
	 */
	private boolean processConfigurationsWithKey(Properties p, String configurationsKey) {
		boolean modified = false;
		List<String> inactiveConfigurations = new ArrayList<>();
		String configurationsValue = (String)p.get(configurationsKey);
		if (configurationsValue != null) {
			List<String> configurations = Stream.of(configurationsValue.split(",")).collect(Collectors.toList());
			for (String configuration: configurations) {
				if (!checkAndRegisterConfigurationType(configuration,ReachedBy.FromSpringFactoriesKey)) {
					if (ConfigOptions.shouldRemoveUnusedAutoconfig()) {
						SpringFeature.log("Excluding auto-configuration (key="+configurationsKey+") =" +configuration);
						inactiveConfigurations.add(configuration);
					}
				}
			}
			if (ConfigOptions.shouldRemoveUnusedAutoconfig() && inactiveConfigurations.size()>0) {
				int totalConfigurations = configurations.size();
				configurations.removeAll(inactiveConfigurations);
				p.put(configurationsKey, String.join(",", configurations));
				SpringFeature.log("Removed "+inactiveConfigurations.size()+" of the "+totalConfigurations+" configurations specified for the key "+configurationsKey);
				modified = true;
			}
		}
		return modified;
	}

	private void loadSpringFactoryFile(URL springFactory, Properties p) {
		try (InputStream is = springFactory.openStream()) {
			p.load(is);
		} catch (IOException e) {
			throw new IllegalStateException("Unable to load spring.factories", e);
		}
	}

	/**
	 * For the specified type (dotted name) determine which types must be
	 * reflectable at runtime. This means looking at annotations and following any
	 * type references within those. These types will be registered. If there are
	 * any issues with accessibility of required types this will return false
	 * indicating it can't be used at runtime.
	 */
	private boolean checkAndRegisterConfigurationType(String typename, ReachedBy reachedBy) {
		return processType(new ProcessingContext(), typename, reachedBy);
	}

	private boolean processType(ProcessingContext pc, String typename, ReachedBy reachedBy) {
		SpringFeature.log("\n\nProcessing type " + typename);
		Type resolvedConfigType = ts.resolveDotted(typename,true);
		if (resolvedConfigType==null) {
			SpringFeature.log("Configuration type " + typename + " is missing - presuming stripped out - considered failed validation");
			return false;
		} 
		boolean b = processType(pc, resolvedConfigType, reachedBy);
		SpringFeature.log("Configuration type " + typename + " has " + (b ? "passed" : "failed") + " validation");
		return b;
	}

	/**
	 * Specific type references are used when registering types not easily identifiable from the
	 * bytecode that we are simply capturing as specific references in the Hints defined
	 * in the configuration module. Used for import selectors, import registrars, configuration.
	 * For @Configuration types here, need only bean method access (not all methods), for 
	 * other types (import selectors/etc) may not need any method reflective access at all
	 * (and no resource access in that case).
	 * @param pc 
	 */
	private boolean registerSpecific(ProcessingContext pc, String typename, AccessDescriptor ad, RequestedConfigurationManager rcm) {
		int accessBits = ad.getAccessBits();
		Type t = ts.resolveDotted(typename, true);
		if (t == null) {
			SpringFeature.log("WARNING: Unable to resolve specific type: " + typename);
			return false;
		} else {
			boolean importRegistrarOrSelector = false;
			try {
				importRegistrarOrSelector = t.isImportRegistrar() || t.isImportSelector();
			} catch (MissingTypeException mte) {
				// something is missing, reflective access not going to work here!
				return false;
			}
			if (importRegistrarOrSelector) {
				int bits = AccessBits.CLASS | AccessBits.DECLARED_CONSTRUCTORS;
				if (t.isImportRegistrar()) { // A kafka registrar seems to need this
					bits|=AccessBits.RESOURCE;
				}
				rcm.requestTypeAccess(typename, bits, ad.getMethodDescriptors(),ad.getFieldDescriptors());
			} else {
				if (AccessBits.isResourceAccessRequired(accessBits)) {
					rcm.requestTypeAccess(typename, AccessBits.RESOURCE);
					rcm.requestTypeAccess(typename, accessBits, ad.getMethodDescriptors(), ad.getFieldDescriptors());
				} else {
					rcm.requestTypeAccess(typename, accessBits, ad.getMethodDescriptors(), ad.getFieldDescriptors());
					// TODO worth limiting it solely to @Bean methods? Need to check how many
					// configuration classes typically have methods that are not @Bean
				}
				if (t.isAtConfiguration()) {
					// This is because of cases like Transaction auto configuration where the
					// specific type names types like ProxyTransactionManagementConfiguration
					// are referred to from the AutoProxyRegistrar CompilationHint.
					// There is a conditional on bean later on the supertype
					// (AbstractTransactionConfiguration)
					// and so we must register proxyXXX and its supertypes as visible.
					registerHierarchy(pc, t, rcm);
				}
			}
			return true;
		}
	}
	
	/**
	 * Captures the route taken when processing a type - we can be more aggressive about
	 * discarding information depending on the route taken. For example it is easy
	 * to modify spring.factories to no longer to refer to an autoconfiguration, but
	 * you can't throw away a configuration if referenced from @Import in another type.
	 * (You can create a shell of a representation for the unnecessary config but
	 * you cannot discard entirely because @Import processing will break).
	 */
	enum ReachedBy {
		FromRoot,
		Import,
		Other,
		FromSpringFactoriesKey, // the type reference was discovered from a spring.factories entry
		FromSpringComponent, // the type reference was discovered when reviewing spring.components
		AtBeanReturnType, // the type reference is the return type of an @Bean method
		NestedReference, // This was discovered as a nested type within some type currently being processed
		HierarchyProcessing, // This was discovered whilst going up the hierarchy from some type currently being processed
		Inferred, // This type was 'inferred' whilst processing a hint (e.g. the class in a @COC usage)
		Specific // This type was explicitly listed in a hint that was processed
	}
	
	private boolean checkJmxConstraint(Type type, ProcessingContext pc) {
		if (ConfigOptions.shouldRemoveJmxSupport()) {
			if (type.getDottedName().toLowerCase().contains("jmx") && 
					!(pc.peekReachedBy()==ReachedBy.Import || pc.peekReachedBy()==ReachedBy.NestedReference)) {
				SpringFeature.log(type.getDottedName()+" FAILED validation - it has 'jmx' in it - returning FALSE");
				if (!ConfigOptions.shouldRemoveUnusedAutoconfig()) {
//					resourcesRegistry.addResources(type.getDottedName().replace(".", "/")+".class");
					collector.addResource(type.getDottedName().replace(".", "/")+".class", false);
				}
				return false;
			}
		}
		return true;
	}


//	private boolean checkConditionalOnEnabledMetricsExport(Type type) {
//		boolean isOK = type.testAnyConditionalOnEnabledMetricsExport();
//		if (!isOK) {
//			SpringFeature.log(type.getDottedName()+" FAILED ConditionalOnEnabledMetricsExport check - returning FALSE");
//			return false;
//		}
//		return true;
//	}
	
	List<String> failedPropertyChecks = new ArrayList<>();
	
	/**
	 * It is possible to ask for property checks to be done at build time - this enables chunks of code to be discarded early
	 * and not included in the image. 
	 * 
	 * @param type the type that may have property related conditions on it
	 * @return true if checks pass, false if one fails and the type should be considered inactive
	 */
	private boolean checkPropertyRelatedConditions(Type type) {
		// Problems observed discarding inner configurations due to eager property checks
		// (configserver sample). Too aggressive, hence the $ check
		if (ConfigOptions.isBuildTimePropertyChecking() && !type.getName().contains("$")) {
			String testResult = type.testAnyConditionalOnProperty();
			if (testResult != null) {
				String message = type.getDottedName()+" FAILED ConditionalOnProperty property check: "+testResult;
				failedPropertyChecks.add(message);
				SpringFeature.log(message);
				return false;
			}
			// These are like a ConditionalOnProperty check but using a special condition to check the property
			testResult = type.testAnyConditionalOnAvailableEndpoint();
			if (testResult != null) {
				String message =  type.getDottedName()+" FAILED ConditionalOnAvailableEndpoint property check: "+testResult;
				failedPropertyChecks.add(message);
				SpringFeature.log(message);
				return false;
			}
			testResult = type.testAnyConditionalOnEnabledMetricsExport();
			if (testResult != null) {
				String message = type.getDottedName()+" FAILED ConditionalOnEnabledMetricsExport property check: "+testResult;
				failedPropertyChecks.add(message);
				SpringFeature.log(message);
				return false;
			}
			testResult = type.testAnyConditionalOnEnabledHealthIndicator();
			if (testResult != null) {
				String message = type.getDottedName()+" FAILED ConditionalOnEnabledHealthIndicator property check: "+testResult;
				failedPropertyChecks.add(message);
				SpringFeature.log(message);
				return false;
			}
		}
		return true;
	}

	private boolean checkConstraintMissingTypesInHierarchyOfThisType(Type type) {
		// Check the hierarchy of the type, if bits are missing resolution of this
		// type at runtime will not work - that suggests that in this particular
		// run the types are not on the classpath and so this type isn't being used.
		Set<String> missingTypes = ts.findMissingTypesInHierarchyOfThisType(type);
		if (!missingTypes.isEmpty()) {
			SpringFeature.log("for " + type.getName() + " missing types in hierarchy are " + missingTypes );
			if (ConfigOptions.shouldRemoveUnusedAutoconfig()) {
				return false;
			}
		}
		return true;
	}

	private boolean isIgnoredConfiguration(Type type) {
		if (ConfigOptions.isIgnoreHintsOnExcludedConfig() && type.isAtConfiguration()) {
			if (isIgnored(type)) {
				SpringFeature.log("skipping hint processing on "+type.getName()+" because it is explicitly excluded in this application");
				return true;
			}
		}
		return false;
	}

	private void checkMissingAnnotationsOnType(Type type) {
		Set<String> missingAnnotationTypes = ts.resolveCompleteFindMissingAnnotationTypes(type);
		if (!missingAnnotationTypes.isEmpty()) {
			// If only the annotations are missing, it is ok to reflect on the existence of
			// the type, it is just not safe to reflect on the annotations on that type.
			SpringFeature.log("for " + type.getName() + " missing annotation types are "
					+ missingAnnotationTypes);
		}
	}
	
	static class ContextEntry {
		private String typename;
		private ReachedBy reachedBy;
		ContextEntry(String typename, ReachedBy reachedBy) {
			this.typename = typename;
			this.reachedBy = reachedBy;
		}
		public String toString() {
			return "[Ctx:"+typename+"-"+reachedBy+"]";
		}
	}

	/**
	 * The ProcessingContext keeps track of the route taken through chasing hints/configurations.
	 * At any point this means we can know why we are processing a type, processing of some types
	 * may need to know about why it is being looked at. For example the superclass of a configuration
	 * may not have @ Configuration on it and yet have @ Bean methods in it.
	 */
	@SuppressWarnings("serial")
	static class ProcessingContext extends Stack<ContextEntry> {
		
		// Keep track of everything seen during use of this ProcessingContext
		private Set<String> visited = new HashSet<>();

		public static ProcessingContext of(String typename, ReachedBy reachedBy) {
			ProcessingContext pc = new ProcessingContext();
			pc.push(new ContextEntry(typename, reachedBy));
			return pc;
		}

		public ReachedBy peekReachedBy() {
			return peek().reachedBy;
		}

		public ContextEntry push(Type type, ReachedBy reachedBy) {
			return push(new ContextEntry(type.getDottedName(), reachedBy));
		}

		public boolean recordVisit(String name) {
			return visited.add(name);
		}

		public int depth() {
			return size();
		}
		
		public String getHierarchyProcessingTopMostTypename() {
			// Double check are we here because we are a parent of some configuration being processed
						// For example: 
						// Analyzing org.springframework.web.reactive.config.WebFluxConfigurationSupport 
						//   reached by 
						// [[Ctx:org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration-FromSpringFactoriesKey], 
						// [Ctx:org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration$EnableWebFluxConfiguration-NestedReference], 
						// [Ctx:org.springframework.web.reactive.config.DelegatingWebFluxConfiguration-HierarchyProcessing], 
						// [Ctx:org.springframework.web.reactive.config.WebFluxConfigurationSupport-HierarchyProcessing]]
			int i = size()-1;
			ContextEntry entry = get(i);
			while (entry.reachedBy==ReachedBy.HierarchyProcessing) {
				if (i==0) {
					break;
				}
				entry = get(--i);
			}
			// Now entry points to the first non HierarchyProcessing case
			return entry.typename;
		}
		
	}

	private boolean processType(ProcessingContext pc, Type type, ReachedBy reachedBy) {
		pc.push(type, reachedBy);
		String typename = type.getDottedName();
		SpringFeature.log("Analyzing " + typename + " reached by " + pc);
		
		if (!checkJmxConstraint(type, pc)) {
			pc.pop();
			return false;
		}
		
		if (!checkPropertyRelatedConditions(type)) {
			pc.pop();
			return false;
		}
		
		if (!checkConstraintMissingTypesInHierarchyOfThisType(type)) {
			pc.pop();
			return false;
		}

//		if (!checkConditionalOnBean(type) || !checkConditionalOnMissingBean(type) || !checkConditionalOnClass(type)) {
//			pc.pop();
//			return false;
//		}
		
		checkMissingAnnotationsOnType(type);

		if (isIgnoredConfiguration(type)) {
			// You may wonder why this is not false? That is because if we return false it will be deleted from
			// spring.factories. Then later when Spring processes the spring exclude autoconfig key that contains this
			// name - it will fail with an error that it doesn't refer to a valid configuration. So here we return true,
			// which isn't optimal but we do skip all the hint processing and further chasing from this configuration.
			pc.pop();
			return true;
		}

		boolean passesTests = true;
		RequestedConfigurationManager accessManager = new RequestedConfigurationManager();
		List<HintApplication> hints = type.getHints();
		printHintSummary(type, hints);
		Map<Type,ReachedBy> toFollow = new HashMap<>();
		for (HintApplication hint : hints) {
			SpringFeature.log("processing hint " + hint);
			passesTests = processExplicitTypeReferencesFromHint(pc, accessManager, hint, toFollow);
			if (!passesTests && ConfigOptions.shouldRemoveUnusedAutoconfig()) {
				break;
			}
			passesTests = processImplicitTypeReferencesFromHint(pc, accessManager, type, hint, toFollow);
			if (!passesTests && ConfigOptions.shouldRemoveUnusedAutoconfig()) {
				break;
			}
			registerAnnotationChain(accessManager, hint.getAnnotationChain());
			if (isHintValidForCurrentMode(hint)) {
				accessManager.requestProxyDescriptors(hint.getProxyDescriptors());
				accessManager.requestResourcesDescriptors(hint.getResourceDescriptors());
				accessManager.requestInitializationDescriptors(hint.getInitializationDescriptors());
			}
		}

		// TODO think about pulling out into extension mechanism for condition evaluators
		// Special handling for @ConditionalOnWebApplication
		if (!type.checkConditionalOnWebApplication() && (pc.depth()==1 || isNestedConfiguration(type))) {
			passesTests = false;
		}

		pc.recordVisit(type.getName());
		if (passesTests || !ConfigOptions.shouldRemoveUnusedAutoconfig()) {
			processHierarchy(pc, accessManager, type);
		}
		
		checkForImportedConfigurations(type, toFollow);

		if (passesTests || !ConfigOptions.shouldRemoveUnusedAutoconfig()) {
			if (type.isAtComponent() && ConfigOptions.isVerifierOn()) {
				type.verifyComponent();
			}
			if (type.isAtConfiguration()) {
				checkForAutoConfigureBeforeOrAfter(type, accessManager);
				String[][] validMethodsSubset = processTypeAtBeanMethods(pc, accessManager, toFollow, type);
				if (validMethodsSubset != null) {
					printMemberSummary("These are the valid @Bean methods",validMethodsSubset);
				}
				configureMethodAccess(accessManager, type, validMethodsSubset,false);
			}
			processTypesToFollow(pc, accessManager, type, reachedBy, toFollow);
			registerAllRequested(accessManager);
		}

		// If the outer type is failing a test, we don't need to go into nested types...
		if (passesTests || !ConfigOptions.shouldRemoveUnusedAutoconfig()) {
			processNestedTypes(pc, type);
		}
		pc.pop();
		return passesTests;
	}

	private void checkForImportedConfigurations(Type type, Map<Type, ReachedBy> toFollow) {
		List<String> importedConfigurations = type.getImportedConfigurations();
		if (importedConfigurations.size()>0) {
			SpringFeature.log("found these imported configurations by "+type.getDottedName()+": "+importedConfigurations);
		}
		for (String importedConfiguration: importedConfigurations) {
			toFollow.put(ts.resolveSlashed(Type.fromLdescriptorToSlashed(importedConfiguration)),ReachedBy.Import);
		}
	}


	// This type might have @AutoConfigureAfter/@AutoConfigureBefore references to
	// other configurations.
	// Those may be getting discarded in this run but need to be class accessible
	// because this configuration needs to refer to them.
	private void checkForAutoConfigureBeforeOrAfter(Type type, RequestedConfigurationManager accessManager) {
		List<Type> boaTypes = type.getAutoConfigureBeforeOrAfter();
		if (boaTypes.size() != 0) {
			SpringFeature.log("registering " + boaTypes.size() + " @AutoConfigureBefore/After references");
			for (Type t : boaTypes) {
				List<Type> transitiveBOAs = t.getAutoConfigureBeforeOrAfter();
				// If this linked configuration also has @AutoconfigureBeforeOrAfter, we need to include it as a
				// resource so that Spring will find these transitive dependencies on further configurations.
				if (transitiveBOAs.size()!=0) {
					accessManager.requestTypeAccess(t.getDottedName(), AccessBits.CLASS|AccessBits.RESOURCE);
				} else {
					accessManager.requestTypeAccess(t.getDottedName(), AccessBits.CLASS);
				}
			}
		}
	}

	private void processTypesToFollow(ProcessingContext pc, RequestedConfigurationManager accessManager,
			Type type, ReachedBy reachedBy, Map<Type, ReachedBy> toFollow) {
		// Follow transitively included inferred types only if necessary:
		for (Map.Entry<Type,ReachedBy> entry : toFollow.entrySet()) {
			Type t = entry.getKey();
			if (ConfigOptions.isAgentMode() && t.isAtConfiguration()) {
				boolean existsInVisibleConfig = existingReflectionConfigContains(t.getDottedName()); // Only worth following if this config is active...
				if (!existsInVisibleConfig) {
					SpringFeature.log("in agent mode not following "+t.getDottedName()+" from "+type.getName()+" - it is not mentioned in existing reflect configuration");
					continue;
				}
			}
			try {
				boolean b = processType(pc, t, entry.getValue());
				if (!b) {
					SpringFeature.log("followed " + t.getName() + " and it failed validation (whilst processing "+type.getDottedName()+" reached by "+reachedBy+")");
//						if (t.isAtConfiguration()) {
//							accessRequestor.removeTypeAccess(t.getDottedName());
//						} else {
						accessManager.reduceTypeAccess(t.getDottedName(),AccessBits.DECLARED_CONSTRUCTORS|AccessBits.CLASS|AccessBits.RESOURCE);
//						}
				}
			} catch (MissingTypeException mte) {
				// Failed to follow that type because some element involved is not on the classpath 
				// (Typically happens when not specifying discard-unused-autconfiguration)
				SpringFeature.log("Unable to completely process followed type "+t.getName()+": "+mte.getMessage());
			}
		}
	}

	private void processHierarchy(ProcessingContext pc, RequestedConfigurationManager accessManager, Type type) {
		SpringFeature.log(">processHierarchy "+type.getShortName());
		String typename = type.getDottedName();
		boolean isConfiguration = type.isAtConfiguration();
		if (!isConfiguration) {
			// Double check are we here because we are a parent of some configuration being processed
			// For example: 
			// Analyzing org.springframework.web.reactive.config.WebFluxConfigurationSupport 
			//   reached by 
			// [[Ctx:org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration-FromSpringFactoriesKey], 
			// [Ctx:org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration$EnableWebFluxConfiguration-NestedReference], 
			// [Ctx:org.springframework.web.reactive.config.DelegatingWebFluxConfiguration-HierarchyProcessing], 
			// [Ctx:org.springframework.web.reactive.config.WebFluxConfigurationSupport-HierarchyProcessing]]
			// TODO [0.9.0] tidyup
			String s2 = pc.getHierarchyProcessingTopMostTypename();
			TypeSystem typeSystem = type.getTypeSystem();
			Type resolve = typeSystem.resolveDotted(s2,true);
			isConfiguration = resolve.isAtConfiguration();
		}
		boolean skip = (ConfigOptions.isFunctionalMode() && (isConfiguration || type.isImportSelector() || type.isCondition()));
		if (!skip) {
			if (ConfigOptions.isFunctionalMode()) {
				System.out.println("WARNING: Functional mode but not skipping: "+type.getDottedName());
			}
			if (type.isImportSelector()) {
				accessManager.requestTypeAccess(typename, Type.inferAccessRequired(type)|AccessBits.RESOURCE);
			} else {
				if (type.isCondition()) {
					accessManager.requestTypeAccess(typename, AccessBits.LOAD_AND_CONSTRUCT|AccessBits.RESOURCE);
				} else {
					accessManager.requestTypeAccess(typename, AccessBits.CLASS|AccessBits.DECLARED_CONSTRUCTORS|AccessBits.DECLARED_METHODS|AccessBits.RESOURCE);//Flag.allDeclaredConstructors);
				}
			}
			// TODO need this guard? if (isConfiguration(configType)) {
			registerHierarchy(pc, type, accessManager);
		}
		recursivelyCallProcessTypeForHierarchyOfType(pc, type);
	}

	private void processNestedTypes(ProcessingContext pc, Type type) {
		SpringFeature.log(" processing nested types of "+type.getName());
		List<Type> nestedTypes = type.getNestedTypes();
		for (Type t : nestedTypes) {
			if (pc.recordVisit(t.getName())) {
				if (!(t.isAtConfiguration() || t.isConditional() || t.isMetaImportAnnotated() || t.isComponent())) {
					continue;
				}
				try {
					boolean b = processType(pc, t, ReachedBy.NestedReference);
					if (!b) {
						SpringFeature.log("verification of nested type " + t.getName() + " failed");
					}
				} catch (MissingTypeException mte) {
					// Failed to process that type because some element involved is not on the classpath 
					// (Typically happens when not specifying discard-unused-autoconfiguration)
					SpringFeature.log("Unable to completely process nested type "+t.getName()+": "+mte.getMessage());
				}
			}
		}
	}

	private boolean processImplicitTypeReferencesFromHint(ProcessingContext pc,
			RequestedConfigurationManager accessRequestor, Type type, HintApplication hint, Map<Type, ReachedBy> toFollow) {
		boolean passesTests = true;
		Map<String, Integer> inferredTypes = hint.getInferredTypes();
		if (inferredTypes.size() > 0) {
			SpringFeature.log("attempting registration of " + inferredTypes.size() + " inferred types");
			for (Map.Entry<String, Integer> inferredType : inferredTypes.entrySet()) {
				String s = inferredType.getKey();
				Type t = ts.resolveDotted(s, true);
				boolean exists = (t != null);
				if (!exists) {
					SpringFeature.log("inferred type " + s + " not found");
				}
				if (exists) {
					// TODO
					// Inferred types are how we follow configuration classes, we don't need to add these - 
					// we could rely on the existing config (from the surefire run) to tell us whether to follow some
					// things here..
					// Do we not follow if it is @Configuration and missing from the existing other file? 
					
					//if (!ConfigOptions.isFunctionalMode()) {
					accessRequestor.requestTypeAccess(s, inferredType.getValue());
					//}
					
					if (hint.isFollow()) {
						SpringFeature.log("will follow " + t);
						ReachedBy reason = isImportHint(hint)?ReachedBy.Import:ReachedBy.Inferred;
						toFollow.put(t,reason);
					}
					// 'reachedBy==ReachedBySpecific' - trying to do the right thing for (in this example) EhCacheCacheConfiguration.
					// It is a specific reference in the hint on the cache selector - but what does this rule break?
					// The problem is 'conditions' - spring will break with this kind of thing:
					// org.springframework.boot.autoconfigure.cache.EhCacheCacheConfiguration$ConfigAvailableCondition
//							org.springframework.beans.factory.BeanDefinitionStoreException: Failed to process import candidates for configuration class [org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration]; nested exception is java.lang.IllegalArgumentException: Could not find class [org.springframework.boot.autoconfigure.cache.EhCacheCacheConfiguration$ConfigAvailableCondition]
//									at org.springframework.context.annotation.ConfigurationClassParser.processImports(ConfigurationClassParser.java:610) ~[na:na]
//									at org.springframework.context.annotation.ConfigurationClassParser.processImports(ConfigurationClassParser.java:583) ~[na:na]
//									at org.springframework.context.annotation.ConfigurationClassParser.doProcessConfigurationClass(ConfigurationClassParser.java:311) ~[na:na]
				} else if (hint.isSkipIfTypesMissing() && (pc.depth() == 1 || isNestedConfiguration(type) /*|| reachedBy==ReachedBy.Specific*/ || pc.peekReachedBy()==ReachedBy.Import)) {
					if (pc.depth()>1) {
						SpringFeature.log("inferred type missing: "+s+" (processing type: "+type.getDottedName()+" reached by "+pc.peekReachedBy()+") - discarding "+type.getDottedName());
					}
					// Notes: if an inferred type is missing, we have to be careful. Although it should suggest we discard
					// the type being processed, that is not always possible depending on how the type being processed
					// was reached.  If the type being processed came from a spring.factories file, fine, we can throw it away
					// and modify spring.factories. But if we started processing this type because it was listed in an @Import
					// reference, we can't throw it away completely because Spring will fail when it can't class load something
					// listed in @Import. What we can do is create a minimal shell of a type (not allow member reflection) but
					// we can't discard it completely. So the checks here are trying to be careful about what we can throw away.
					// Because nested configuration is discovered solely by reflecting on outer configuration, we can discard
					// any types being processed that were reached by digging into nested types.
					passesTests = false;
					// Once failing, no need to process other hints
					if (ConfigOptions.shouldRemoveUnusedAutoconfig()) {
						break;
					}
				}
			}
		}
		return passesTests;
	}

	private boolean processExplicitTypeReferencesFromHint(ProcessingContext pc, 
			RequestedConfigurationManager accessRequestor, HintApplication hint, Map<Type, ReachedBy> toFollow) {
		boolean passesTests = true;
		boolean hintExplicitReferencesValidInCurrentMode = isHintValidForCurrentMode(hint);
		if (hintExplicitReferencesValidInCurrentMode) {
			Map<String, AccessDescriptor> specificNames = hint.getSpecificTypes();
			if (specificNames.size() > 0) {
				SpringFeature.log("attempting registration of " + specificNames.size() + " specific types");
				for (Map.Entry<String, AccessDescriptor> specificNameEntry : specificNames.entrySet()) {
					String specificTypeName = specificNameEntry.getKey();
					if (!registerSpecific(pc, specificTypeName, specificNameEntry.getValue(), accessRequestor)) {
						if (hint.isSkipIfTypesMissing()) {
							passesTests = false;
							if (ConfigOptions.shouldRemoveUnusedAutoconfig()) {
								break;
							}
						}
					} else {
						if (hint.isFollow()) {
							// TODO I suspect only certain things need following, specific types lists could
							// specify that in a suffix (like access required)
							SpringFeature.log( "will follow specific type reference " + specificTypeName);
							toFollow.put(ts.resolveDotted(specificTypeName),ReachedBy.Specific);
						}
					}
				}
			}
		}
		return passesTests;
	}

	/**
	 * This handles when some @Bean methods on type do not need runtime access (because they have conditions
	 * on them that failed when checked). In this case the full list of methods should be spelled out
	 * excluding the unnecessary ones. If validMethodsSubset is null then all @Bean methods are valid.
	 */
	private void configureMethodAccess(RequestedConfigurationManager accessRequestor, Type type,
			String[][] validMethodsSubset, boolean atBeanMethodsOnly) {
		SpringFeature.log("computing full reflective method access list for "+type.getDottedName()+" validMethodSubset incoming = "+MethodDescriptor.toString(validMethodsSubset));
//		boolean onlyPublicMethods = (accessRequestor.getTypeAccessRequestedFor(type.getDottedName())&AccessBits.PUBLIC_METHODS)!=0;
		boolean onlyNonPrivateMethods = true;
		List<String> toMatchAgainst = new ArrayList<>();
		if (validMethodsSubset!=null) {
			for (String[] validMethod: validMethodsSubset) {
				toMatchAgainst.add(String.join("::",validMethod));
			}
		}
		List<String[]> allRelevantMethods = new ArrayList<>();
		SpringFeature.log("There are "+allRelevantMethods.size()+" possible members");
		for (Method method : type.getMethods()) {
			if (!method.getName().equals("<init>") && !method.getName().equals("<clinit>")) { // ignore constructors
				if (onlyNonPrivateMethods && method.isPrivate()) {
					SpringFeature.log("checking '"+method.getName()+method.getDesc()+"' -> private - skipping");
					continue;
				}
//				if (onlyPublicMethods && !method.isPublic()) {
//					continue;
//				}
				if (method.hasUnresolvableParams()) {
					SpringFeature.log("checking '"+method.getName()+method.getDesc()+"' -> unresolvable parameters, ignoring");
					continue;
				}
				String[] candidate = method.asConfigurationArray();
				if (method.markedAtBean()) {
					if (validMethodsSubset != null && !toMatchAgainst.contains(String.join("::", candidate))) {
						continue;
					}
				} else {
					if (atBeanMethodsOnly) {
						continue;
					}
				}
				allRelevantMethods.add(candidate);
			}
		}
		String[][] methods = allRelevantMethods.toArray(new String[0][]);
		printMemberSummary("These will be granted reflective access:", methods);
		accessRequestor.addMethodDescriptors(type.getDottedName(), methods);
	}

	private void printMemberSummary(String prefix, String[][] processTypeAtBeanMethods) {
		if (processTypeAtBeanMethods != null) {
			SpringFeature.log(prefix+" member summary: " + processTypeAtBeanMethods.length);
			for (int i = 0; i < processTypeAtBeanMethods.length; i++) {
				String[] member = processTypeAtBeanMethods[i];
				StringBuilder s = new StringBuilder();
				s.append(member[0]).append("(");
				for (int p = 1; p < member.length; p++) {
					if (p > 1) {
						s.append(",");
					}
					s.append(member[p]);
				}
				s.append(")");
				SpringFeature.log(s.toString());
			}
		}
	}

	private boolean isImportHint(HintApplication hint) {
		List<Type> annotationChain = hint.getAnnotationChain();
		return annotationChain.get(annotationChain.size()-1).equals(ts.getType_Import());
	}

	private void printHintSummary(Type type, List<HintApplication> hints) {
		if (hints.size() != 0) {
			SpringFeature.log("found "+ hints.size() + " hints on " + type.getDottedName()+":");
			for (int h = 0; h < hints.size(); h++) {
				SpringFeature.log((h + 1) + ") " + hints.get(h));
			}
		} else {
			SpringFeature.log("no hints on " + type.getName());
		}
	}

	/**
	 * Process any @Bean methods in a configuration type. For these methods we need to ensure
	 * reflective access to the return types involved as well as any annotations on the
	 * method. It is also important to check any Conditional annotations on the method as
	 * a ConditionalOnClass may fail at build time and the whole @Bean method can be ignored.
	 */
	private String[][] processTypeAtBeanMethods(ProcessingContext pc, RequestedConfigurationManager rcm,
			Map<Type,ReachedBy> toFollow, Type type) {
		List<String[]> passingMethodsSubset = new ArrayList<>();
		boolean anyMethodFailedValidation = false;
		// This is computing how many methods we are exposing unnecessarily via reflection by 
		// specifying allDeclaredMethods for this type rather than individually specifying them.
		// A high number indicates we should perhaps do more to be selective.
		int totalMethodCount = type.getMethodCount(false);
		List<Method> atBeanMethods = type.getMethodsWithAtBean();
		int rogue = (totalMethodCount - atBeanMethods.size());
		if (rogue != 0) {
			SpringFeature.log(
					"WARNING: Methods unnecessarily being exposed by reflection on this config type "
					+ type.getName() + " = " + rogue + " (total methods including @Bean ones:" + totalMethodCount + ")");
		}

		if (atBeanMethods.size() != 0) {
			SpringFeature.log("processing " + atBeanMethods.size() + " @Bean methods on type "+type.getDottedName());
		}

		for (Method atBeanMethod : atBeanMethods) {
			RequestedConfigurationManager methodRCM = new RequestedConfigurationManager();
			Map<Type, ReachedBy> additionalFollows = new HashMap<>();
			boolean passesTests = true;
			
			// boolean methodAnnotatedAtConfigurationProperties = atBeanMethod.hasAnnotation(Type.AtConfigurationProperties, false);
			
			// Check if resolvable...
			boolean hasUnresolvableTypesInSignature = atBeanMethod.hasUnresolvableTypesInSignature();
			if (hasUnresolvableTypesInSignature) {
				anyMethodFailedValidation = true;
				continue;
			}
			
			Type returnType = atBeanMethod.getReturnType();
			if (returnType == null) {
				// null means that type is not on the classpath so skip further analysis of this method...
				continue;
			} else {
				methodRCM.requestTypeAccess(returnType.getDottedName(), AccessBits.CLASS | AccessBits.DECLARED_CONSTRUCTORS);
			}

			// Example method being handled here:
			//	@Bean
			//	@ConditionalOnClass(name = "org.springframework.security.authentication.event.AbstractAuthenticationEvent")
			//	@ConditionalOnMissingBean(AbstractAuthenticationAuditListener.class)
			//	public AuthenticationAuditListener authenticationAuditListener() throws Exception {
			//		return new AuthenticationAuditListener();
			//	}
			if (!ConfigOptions.isSkipAtBeanHintProcessing()) {
				List<HintApplication> methodHints = atBeanMethod.getHints();
				SpringFeature.log("@Bean method "+atBeanMethod + " hints: #"+methodHints.size());
				for (int i=0;i<methodHints.size();i++) {
					SpringFeature.log((i+1)+") "+methodHints.get(i));
				}
				for (int h=0;h<methodHints.size() && passesTests;h++) {
					HintApplication hint = methodHints.get(h);
					SpringFeature.log("processing hint " + hint);

					Map<String, AccessDescriptor> specificNames = hint.getSpecificTypes();
					if (specificNames.size() != 0) {
						SpringFeature.log("handling " + specificNames.size() + " specific types");
						for (Map.Entry<String, AccessDescriptor> specificNameEntry : specificNames.entrySet()) {
							registerSpecific(pc, specificNameEntry.getKey(),
									specificNameEntry.getValue(), methodRCM);
						}
					}

					Map<String, Integer> inferredTypes = hint.getInferredTypes();
					if (inferredTypes.size()!=0) {
						SpringFeature.log("handling " + inferredTypes.size() + " inferred types");
						for (Map.Entry<String, Integer> inferredType : inferredTypes.entrySet()) {
							String s = inferredType.getKey();
							Type t = ts.resolveDotted(s, true);
							boolean exists = (t != null);
							if (!exists) {
								SpringFeature.log("inferred type " + s + " not found");
							} else {
								SpringFeature.log("inferred type " + s + " found, will get accessibility " + AccessBits.toString(inferredType.getValue()));
							}
							if (exists) {
								// TODO if already there, should we merge access required values?
								methodRCM.requestTypeAccess(s, inferredType.getValue());
								if (hint.isFollow()) {
									additionalFollows.put(t,ReachedBy.Other);
								}
							} else if (hint.isSkipIfTypesMissing()) {
								passesTests = false;
								break;
							}
						}
					}
					if (passesTests && !ConfigOptions.isFunctionalMode()) {
						List<Type> annotationChain = hint.getAnnotationChain();
						registerAnnotationChain(methodRCM, annotationChain);
					}
				}
			}

			// Register other runtime visible annotations from the @Bean method. For example
			// this ensures @Role is visible on:
			// @Bean
			// @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
			// @ConditionalOnMissingBean(Validator.class)
			// public static LocalValidatorFactoryBean defaultValidator() {
			if (passesTests) {
				List<Type> annotationsOnMethod = atBeanMethod.getAnnotationTypes();
				for (Type annotationOnMethod : annotationsOnMethod) {
					methodRCM.requestTypeAccess(annotationOnMethod.getDottedName(), AccessBits.ANNOTATION);
				}
			} 
			if (passesTests) {
				try {
					passingMethodsSubset.add(atBeanMethod.asConfigurationArray());
					toFollow.putAll(additionalFollows);
					if (returnType.isComponent()) {
						toFollow.put(returnType, ReachedBy.AtBeanReturnType);
					}
					rcm.mergeIn(methodRCM);
					SpringFeature.log("method passed checks - adding configuration for it");
				} catch (IllegalStateException ise) {
					// usually if asConfigurationArray() fails - due to an unresolvable type - it indicates
					// this method is no use
					SpringFeature.log("method failed checks - ise on "+atBeanMethod.getName()+" so ignoring");
					anyMethodFailedValidation = true;
				}
			} else {
				anyMethodFailedValidation = true;
				SpringFeature.log("method failed checks - not adding configuration for it");
			}
		}
		if (anyMethodFailedValidation) {
			// Return the subset that passed
			return passingMethodsSubset.toArray(new String[0][]);
		} else {
			return null;
		}
	}

	private void recursivelyCallProcessTypeForHierarchyOfType(ProcessingContext pc, Type type) {
		Type s = type.getSuperclass();
		while (s != null) {
			if (s.getName().equals("java/lang/Object")) {
				break;
			}
			if (pc.recordVisit(s.getName())) {
				boolean b = processType(pc, s, ReachedBy.HierarchyProcessing);
				if (!b) {
					SpringFeature.log("WARNING: whilst processing type " + type.getName()
							+ " superclass " + s.getName() + " verification failed");
				}
			} else {
				break;
			}
			s = s.getSuperclass();
		}
	}

	private void registerAllRequested(RequestedConfigurationManager accessRequestor) {
		registerAllRequested(0, accessRequestor);
	}
	
	// In an attempt to reduce verbosity helps avoid reporting identical messages over and over
	private static Map<String, Integer> reflectionConfigurationAlreadyAdded = new HashMap<>();

	private void registerAllRequested(int depth, RequestedConfigurationManager accessRequestor) {
		for (InitializationDescriptor initializationDescriptor : accessRequestor.getRequestedInitializations()) {
			initializationHandler.registerInitializationDescriptor(initializationDescriptor);
		}
		for (ProxyDescriptor proxyDescriptor : accessRequestor.getRequestedProxies()) {
			dynamicProxiesHandler.addProxy(proxyDescriptor);
		}
		for (org.springframework.nativex.type.ResourcesDescriptor rd : accessRequestor.getRequestedResources()) {
			registerResourcesDescriptor(rd);
		}
		for (Map.Entry<String, Integer> accessRequest : accessRequestor.getRequestedTypeAccesses()) {
			String dname = accessRequest.getKey();
			int requestedAccess = accessRequest.getValue();
			List<org.springframework.nativex.type.MethodDescriptor> methods = accessRequestor.getMethodAccessRequestedFor(dname);
			
			// TODO promote this approach to a plugin if becomes a little more common...
			if (dname.equals("org.springframework.boot.autoconfigure.web.ServerProperties$Jetty")) { // See EmbeddedJetty @COC check
				if (!ts.canResolve("org/eclipse/jetty/webapp/WebAppContext")) {
					System.out.println("Reducing access on "+dname+" because WebAppContext not around");
					requestedAccess = AccessBits.CLASS;
				}
			}

			if (dname.equals("org.springframework.boot.autoconfigure.web.ServerProperties$Undertow")) { // See EmbeddedUndertow @COC check
				if (!ts.canResolve("io/undertow/Undertow")) {
					System.out.println("Reducing access on "+dname+" because Undertow not around");
					requestedAccess = AccessBits.CLASS;
				}
			}

			if (dname.equals("org.springframework.boot.autoconfigure.web.ServerProperties$Tomcat")) { // See EmbeddedTomcat @COC check
				if (!ts.canResolve("org/apache/catalina/startup/Tomcat")) {
					System.out.println("Reducing access on "+dname+" because Tomcat not around");
					requestedAccess = AccessBits.CLASS;
				}
			}

			if (dname.equals("org.springframework.boot.autoconfigure.web.ServerProperties$Netty")) { // See EmbeddedNetty @COC check
				if (!ts.canResolve("reactor/netty/http/server/HttpServer")) {
					System.out.println("Reducing access on "+dname+" because HttpServer not around");
					requestedAccess = AccessBits.CLASS;
				}
			}

			// Let's produce a message if this computed value is also in reflect.json
			// This is a sign we can probably remove that entry from reflect.json (maybe
			// depend if inferred access required matches declared)
			if (reflectionHandler.getConstantData().hasClassDescriptor(dname)) {
				System.out.println("This is in the constant data, does it need to stay in there? " + dname
						+ "  (dynamically requested access is " + requestedAccess + ")");
			}

			// Only log new info that is being added at this stage to keep logging down
			Integer access = reflectionConfigurationAlreadyAdded.get(dname);
			if (access == null) {
				SpringFeature.log(spaces(depth) + "configuring reflective access to " + dname + "   " + AccessBits.toString(requestedAccess)+
						(methods==null?"":" mds="+methods));
				reflectionConfigurationAlreadyAdded.put(dname, requestedAccess);
			} else {
				int extraAccess = AccessBits.compareAccess(access,requestedAccess);
				if (extraAccess>0) {
					SpringFeature.log(spaces(depth) + "configuring reflective access, adding access for " + dname + " of " + 
							AccessBits.toString(extraAccess)+" (total now: "+AccessBits.toString(requestedAccess)+")");
					reflectionConfigurationAlreadyAdded.put(dname, access);
				}
			}
			Flag[] flags = AccessBits.getFlags(requestedAccess);
			Type rt = ts.resolveDotted(dname, true);
			if (ConfigOptions.isFunctionalMode()) {
				if (rt.isAtConfiguration() || rt.isConditional() || rt.isCondition() ||
						rt.isImportSelector() || rt.isImportRegistrar()) {
					SpringFeature.log("In functional mode, not actually adding reflective access for "+dname);
					continue;
				}
			}

			if (methods != null) {
				// methods are explicitly specified, remove them from flags
				SpringFeature.log(dname+" has #"+methods.size()+" methods directly specified so removing any general method access needs");
				flags = filterFlags(flags, Flag.allDeclaredMethods, Flag.allPublicMethods);
			}
//			SpringFeature.log(spaces(depth) + "fixed flags? "+Flag.toString(flags));
//			SpringFeature.log(depth, "ms: "+methods);
			reflectionHandler.addAccess(dname, MethodDescriptor.toStringArray(methods), null, true, flags);
			/*
			if (flags != null && flags.length == 1 && flags[0] == Flag.allDeclaredConstructors) {
				Type resolvedType = ts.resolveDotted(dname, true);
//				if (resolvedType != null && resolvedType.hasOnlySimpleConstructor()) {
//					reflectionHandler.addAccess(dname, new String[][] { { "<init>" } },null, true);
//				} else {
//				}
			} else {
				reflectionHandler.addAccess(dname, null, null, true, flags);
			}
			*/
			if (AccessBits.isResourceAccessRequired(requestedAccess)) {
//				resourcesRegistry.addResources(
				collector.addResource(
					dname.replace(".", "/").replace("$", ".").replace("[", "\\[").replace("]", "\\]") + ".class", false);
			}
		}
	}
	
	private Flag[] filterFlags(Flag[] flags, Flag... toFilter) {
		List<Flag> ok = new ArrayList<>();
		for (Flag flag: flags) {
			boolean skip  =false;
			for (Flag f: toFilter) {
				if (f==flag) {
					skip = true;
				}
			}
			if (!skip) {
				ok.add(flag);
			}
		}
		return ok.toArray(new Flag[0]);
	}

	private boolean isHintValidForCurrentMode(HintApplication hint) {
		Mode currentMode = ConfigOptions.getMode();
		if (!hint.applyToFunctional() && currentMode == Mode.FUNCTIONAL) {
			return false;
		}
		return true;
	}
	
	private boolean isHintValidForCurrentMode(HintDeclaration hint) {
		Mode currentMode = ConfigOptions.getMode();
		if (!hint.applyToFunctional() && currentMode==Mode.FUNCTIONAL) {
			return false;
		}
		return true;
	}

	private boolean isIgnored(Type configurationType) {
		List<String> excludedAutoConfig = ts.getExcludedAutoConfigurations();
		return excludedAutoConfig.contains(configurationType.getDottedName());
	}

	private boolean existingReflectionConfigContains(String s) {
		Map<String, ReflectionDescriptor> reflectionConfigurationsOnClasspath = ts.getReflectionConfigurationsOnClasspath();
		for (ReflectionDescriptor rd: reflectionConfigurationsOnClasspath.values()) {
			if (rd.hasClassDescriptor(s)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Crude guess at nested configuration.
	 */
	private boolean isNestedConfiguration(Type type) {
		boolean b = type.isAtConfiguration() && type.getEnclosingType()!=null;
		return b;
	}

	private void registerAnnotationChain(RequestedConfigurationManager tar, List<Type> annotationChain) {
		SpringFeature.log("attempting registration of " + annotationChain.size()
				+ " elements of annotation hint chain");
		for (int i = 0; i < annotationChain.size(); i++) {
			// i=0 is the annotated type, i>0 are all annotation types
			Type t = annotationChain.get(i);
			if (i==0 && ConfigOptions.isAgentMode()) {
				boolean beingReflectedUponInIncomingConfiguration = existingReflectionConfigContains(t.getDottedName());
				if (!beingReflectedUponInIncomingConfiguration) {
					SpringFeature.log("In agent mode skipping "+t.getDottedName()+" because in already existing configuration");
					break;
				}
			}
			tar.requestTypeAccess(t.getDottedName(), Type.inferAccessRequired(t));
		}
	}

	private void log(String msg) {
		System.out.println(msg);
	}

	private String spaces(int depth) {
		return "                                                  ".substring(0, depth * 2);
	}

}
