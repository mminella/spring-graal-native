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
package org.springframework.graal.type;

import java.lang.reflect.Modifier;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.Stack;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.springframework.graal.extension.NativeImageHint;
import org.springframework.graal.extension.NativeImageHints;
import org.springframework.graal.support.ConfigOptions;
import org.springframework.graal.support.SpringFeature;

/**
 * @author Andy Clement
 */
public class Type {

	public final static String AtResponseBody = "Lorg/springframework/web/bind/annotation/ResponseBody;";
	public final static String AtMapping = "Lorg/springframework/web/bind/annotation/Mapping;";
	public final static String AtTransactional = "Lorg/springframework/transaction/annotation/Transactional;";
	public final static String AtJavaxTransactional = "Ljavax/transaction/Transactional;";
	public final static String AtBean = "Lorg/springframework/context/annotation/Bean;";
	public final static String AtConditionalOnClass = "Lorg/springframework/boot/autoconfigure/condition/ConditionalOnClass;";
	public final static String AtConditionalOnMissingBean = "Lorg/springframework/boot/autoconfigure/condition/ConditionalOnMissingBean;";
	public final static String AtConfiguration = "Lorg/springframework/context/annotation/Configuration;";
	public final static String AtRepository = "Lorg/springframework/stereotype/Repository;";
	public final static String AtEnableConfigurationProperties = "Lorg/springframework/boot/context/properties/EnableConfigurationProperties;";
	public final static String AtImports = "Lorg/springframework/context/annotation/Import;";
	public final static String AtEnableBatchProcessing = "Lorg/springframework/batch/core/configuration/annotation/EnableBatchProcessing;";
	public final static String ImportBeanDefinitionRegistrar = "Lorg/springframework/context/annotation/ImportBeanDefinitionRegistrar;";
	public final static String ImportSelector = "Lorg/springframework/context/annotation/ImportSelector;";
	public final static String Condition = "Lorg/springframework/context/annotation/Condition;";

	public final static Type MISSING = new Type(null, null, 0);

	public final static Type[] NO_INTERFACES = new Type[0];

	protected static Set<String> validBoxing = new HashSet<String>();

	private TypeSystem typeSystem;

	private ClassNode node;

	private Type[] interfaces;

	private String name;

	private int dimensions = 0; // >0 for array types

	private Type(TypeSystem typeSystem, ClassNode node, int dimensions) {
		this.typeSystem = typeSystem;
		this.node = node;
		this.dimensions = dimensions;
		if (node != null) {
			this.name = node.name;
			for (int i = 0; i < dimensions; i++) {
				this.name += "[]";
			}
		}
	}

	public static Type forClassNode(TypeSystem typeSystem, ClassNode node, int dimensions) {
		return new Type(typeSystem, node, dimensions);
	}

	/**
	 * @return typename in slashed form (aaa/bbb/ccc/Ddd$Eee)
	 */
	public String getName() {
		return name;
	}

	public String getDottedName() {
		return name.replace("/", ".");
	}

	public Type getSuperclass() {
		if (dimensions > 0) {
			return typeSystem.resolveSlashed("java/lang/Object");
		}
		if (node.superName == null) {
			return null;
		}
		return typeSystem.resolveSlashed(node.superName);
	}

	@Override
	public String toString() {
		return "Type:" + getName();
	}

	public Type[] getInterfaces() {
		if (interfaces == null) {
			if (dimensions != 0) {
				interfaces = new Type[] { typeSystem.resolveSlashed("java/lang/Cloneable"),
						typeSystem.resolveSlashed("java/io/Serializable") };
			} else {
				List<String> itfs = node.interfaces;
				if (itfs.size() == 0) {
					interfaces = NO_INTERFACES;
				} else {
					interfaces = new Type[itfs.size()];
					for (int i = 0; i < itfs.size(); i++) {
						interfaces[i] = typeSystem.resolveSlashed(itfs.get(i));
					}
				}
			}
		}
		return interfaces;
	}

	/** @return List of slashed interface types */
	public List<String> getInterfacesStrings() {
		if (dimensions != 0) {
			List<String> itfs = new ArrayList<>();
			itfs.add("java/lang/Cloneable");
			itfs.add("java/io/Serializable");
			return itfs;
		} else {
			return node.interfaces;
		}
	}

	/** @return slashed supertype name */
	public String getSuperclassString() {
		return dimensions > 0 ? "java/lang/Object" : node.superName;
	}

	/**
	 * Compute all the types referenced in the signature of this type.
	 * 
	 * @return
	 */
	public List<String> getTypesInSignature() {
		if (dimensions > 0) {
			return Collections.emptyList();
		} else if (node.signature == null) {
			// With no generic signature it is just superclass and interfaces
			List<String> ls = new ArrayList<>();
			if (node.superName != null) {
				ls.add(node.superName);
			}
			if (node.interfaces != null) {
				ls.addAll(node.interfaces);
			}
			return ls;
		} else {
			// Pull out all the types from the generic signature
			SignatureReader reader = new SignatureReader(node.signature);
			TypeCollector tc = new TypeCollector();
			reader.accept(tc);
			return tc.getTypes();
		}
	}

	static class TypeCollector extends SignatureVisitor {

		List<String> types = null;

		public TypeCollector() {
			super(Opcodes.ASM7);
		}

		@Override
		public void visitClassType(String name) {
			if (types == null) {
				types = new ArrayList<String>();
			}
			types.add(name);
		}

		public List<String> getTypes() {
			if (types == null) {
				return Collections.emptyList();
			} else {
				return types;
			}
		}

	}

	public boolean extendsClass(String clazzname) {
		Type superclass = getSuperclass();
		while (superclass != null) {
			if (superclass.getDescriptor().equals(clazzname)) {
				return true;
			}
			superclass = superclass.getSuperclass();
		}
		return false;
	}

	public boolean implementsInterface(String interfaceName) {
		Type[] interfacesToCheck = getInterfaces();
		for (Type interfaceToCheck : interfacesToCheck) {
			if (interfaceToCheck != null) {
				if (interfaceToCheck.getName().equals(interfaceName)) {
					return true;
				}
				if (interfaceToCheck.implementsInterface(interfaceName)) {
					return true;
				}
			}
		}
		Type superclass = getSuperclass();
		while (superclass != null) {
			if (superclass.implementsInterface(interfaceName)) {
				return true;
			}
			superclass = superclass.getSuperclass();
		}
		return false;
	}

	public List<Method> getMethodsWithAnnotation(String string) {
		// System.out.println("looking through methods "+node.methods+" for "+string);
		return dimensions > 0 ? Collections.emptyList()
				: node.methods.stream().filter(m -> hasAnnotation(m, string)).map(m -> wrap(m))
						.collect(Collectors.toList());
	}

	public List<Method> getMethodsWithAnnotation(String string, boolean checkMetaUsage) {
		if (dimensions > 0) {
			return Collections.emptyList();
		}
		List<Method> results = new ArrayList<>();
		if (node.methods != null) {
			for (MethodNode mn : node.methods) {
				if (hasAnnotation(mn, string, checkMetaUsage)) {
					if (results == null) {
						results = new ArrayList<>();
					}
					results.add(wrap(mn));
				}
			}
		}
		return results == null ? Collections.emptyList() : results;
	}

	public List<Method> getMethodsWithAtBean() {
		return getMethodsWithAnnotation(AtBean);
	}

	private Method wrap(MethodNode mn) {
		return new Method(mn, typeSystem);
	}

	private boolean hasAnnotation(MethodNode m, String string) {
		List<AnnotationNode> visibleAnnotations = m.visibleAnnotations;
		Optional<AnnotationNode> findAny = visibleAnnotations == null ? Optional.empty()
				: visibleAnnotations.stream().filter(a -> a.desc.equals(string)).findFirst();
		return findAny.isPresent();
	}

	public boolean hasAnnotation(String lAnnotationDescriptor, boolean checkMetaUsage) {
		Set<String> seen = new HashSet<>();
		return hasAnnotationHelper(lAnnotationDescriptor, checkMetaUsage, seen);
	}

	private boolean hasAnnotationHelper(String lAnnotationDescriptor, boolean checkMetaUsage, Set<String> seen) {
		if (node.visibleAnnotations != null) {
			for (AnnotationNode an : node.visibleAnnotations) {
				if (an.desc.equals(lAnnotationDescriptor)) {
					return true;
				}
				if (checkMetaUsage && seen.add(lAnnotationDescriptor)) {
					Type t = typeSystem.Lresolve(an.desc);
					if (t != null) {
						boolean b = t.hasAnnotationHelper(lAnnotationDescriptor, checkMetaUsage, seen);
						if (b) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	private boolean hasAnnotation(MethodNode mn, String lAnnotationDescriptor, boolean checkMetaUsage) {
		if (checkMetaUsage) {
			return findMetaAnnotationUsage(toAnnotations(mn.visibleAnnotations), lAnnotationDescriptor);
		} else {
			List<AnnotationNode> vAnnotations = mn.visibleAnnotations;
			if (vAnnotations != null) {
				for (AnnotationNode an : vAnnotations) {
					if (an.desc.equals(lAnnotationDescriptor)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private List<Type> toAnnotations(List<AnnotationNode> visibleAnnotations) {
		if (visibleAnnotations==null) {
			return Collections.emptyList();
		}
		List<Type> result = new ArrayList<>();
		for (AnnotationNode an: visibleAnnotations) {
			result.add(typeSystem.Lresolve(an.desc));
		}
		return result;
	}

	private boolean findMetaAnnotationUsage(List<Type> toSearch, String lAnnotationDescriptor) {
		return findMetaAnnotationUsageHelper(toSearch, lAnnotationDescriptor, new HashSet<>());
	}

	private boolean findMetaAnnotationUsageHelper(List<Type> toSearch, String lAnnotationDescriptor, Set<String> seen) {
		if (toSearch == null) {
			return false;
		}
		for (Type an : toSearch) {
			if (an.getDescriptor().equals(lAnnotationDescriptor)) {
				return true;
			}
			if (seen.add(an.getName())) {
				boolean isMetaAnnotated = findMetaAnnotationUsageHelper(an.getAnnotations(), lAnnotationDescriptor,
						seen);
				if (isMetaAnnotated) {
					return true;
				}
			}
		}
		return false;
	}

	static {
		validBoxing.add("Ljava/lang/Byte;B");
		validBoxing.add("Ljava/lang/Character;C");
		validBoxing.add("Ljava/lang/Double;D");
		validBoxing.add("Ljava/lang/Float;F");
		validBoxing.add("Ljava/lang/Integer;I");
		validBoxing.add("Ljava/lang/Long;J");
		validBoxing.add("Ljava/lang/Short;S");
		validBoxing.add("Ljava/lang/Boolean;Z");
		validBoxing.add("BLjava/lang/Byte;");
		validBoxing.add("CLjava/lang/Character;");
		validBoxing.add("DLjava/lang/Double;");
		validBoxing.add("FLjava/lang/Float;");
		validBoxing.add("ILjava/lang/Integer;");
		validBoxing.add("JLjava/lang/Long;");
		validBoxing.add("SLjava/lang/Short;");
		validBoxing.add("ZLjava/lang/Boolean;");
	}

	public boolean isAssignableFrom(Type other) {
		if (other.isPrimitiveType()) {
			if (validBoxing.contains(this.getName() + other.getName())) {
				return true;
			}
		}
		if (this == other) {
			return true;
		}

		if (this.getName().equals("Ljava/lang/Object;")) {
			return true;
		}

//		if (!isTypeVariableReference()
//				&& other.getSignature().equals("Ljava/lang/Object;")) {
//			return false;
//		}

//		boolean thisRaw = this.isRawType();
//		if (thisRaw && other.isParameterizedOrGenericType()) {
//			return isAssignableFrom(other.getRawType());
//		}
//
//		boolean thisGeneric = this.isGenericType();
//		if (thisGeneric && other.isParameterizedOrRawType()) {
//			return isAssignableFrom(other.getGenericType());
//		}
//
//		if (this.isParameterizedType()) {
//			// look at wildcards...
//			if (((ReferenceType) this.getRawType()).isAssignableFrom(other)) {
//				boolean wildcardsAllTheWay = true;
//				ResolvedType[] myParameters = this.getResolvedTypeParameters();
//				for (int i = 0; i < myParameters.length; i++) {
//					if (!myParameters[i].isGenericWildcard()) {
//						wildcardsAllTheWay = false;
//					} else {
//						BoundedReferenceType boundedRT = (BoundedReferenceType) myParameters[i];
//						if (boundedRT.isExtends() || boundedRT.isSuper()) {
//							wildcardsAllTheWay = false;
//						}
//					}
//				}
//				if (wildcardsAllTheWay && !other.isParameterizedType()) {
//					return true;
//				}
//				// we have to match by parameters one at a time
//				ResolvedType[] theirParameters = other
//						.getResolvedTypeParameters();
//				boolean parametersAssignable = true;
//				if (myParameters.length == theirParameters.length) {
//					for (int i = 0; i < myParameters.length
//							&& parametersAssignable; i++) {
//						if (myParameters[i] == theirParameters[i]) {
//							continue;
//						}
//						// dont do this: pr253109
//						// if
//						// (myParameters[i].isAssignableFrom(theirParameters[i],
//						// allowMissing)) {
//						// continue;
//						// }
//						ResolvedType mp = myParameters[i];
//						ResolvedType tp = theirParameters[i];
//						if (mp.isParameterizedType()
//								&& tp.isParameterizedType()) {
//							if (mp.getGenericType().equals(tp.getGenericType())) {
//								UnresolvedType[] mtps = mp.getTypeParameters();
//								UnresolvedType[] ttps = tp.getTypeParameters();
//								for (int ii = 0; ii < mtps.length; ii++) {
//									if (mtps[ii].isTypeVariableReference()
//											&& ttps[ii]
//													.isTypeVariableReference()) {
//										TypeVariable mtv = ((TypeVariableReferenceType) mtps[ii])
//												.getTypeVariable();
//										boolean b = mtv
//												.canBeBoundTo((ResolvedType) ttps[ii]);
//										if (!b) {// TODO incomplete testing here
//													// I think
//											parametersAssignable = false;
//											break;
//										}
//									} else {
//										parametersAssignable = false;
//										break;
//									}
//								}
//								continue;
//							} else {
//								parametersAssignable = false;
//								break;
//							}
//						}
//						if (myParameters[i].isTypeVariableReference()
//								&& theirParameters[i].isTypeVariableReference()) {
//							TypeVariable myTV = ((TypeVariableReferenceType) myParameters[i])
//									.getTypeVariable();
//							// TypeVariable theirTV =
//							// ((TypeVariableReferenceType)
//							// theirParameters[i]).getTypeVariable();
//							boolean b = myTV.canBeBoundTo(theirParameters[i]);
//							if (!b) {// TODO incomplete testing here I think
//								parametersAssignable = false;
//								break;
//							} else {
//								continue;
//							}
//						}
//						if (!myParameters[i].isGenericWildcard()) {
//							parametersAssignable = false;
//							break;
//						} else {
//							BoundedReferenceType wildcardType = (BoundedReferenceType) myParameters[i];
//							if (!wildcardType.alwaysMatches(theirParameters[i])) {
//								parametersAssignable = false;
//								break;
//							}
//						}
//					}
//				} else {
//					parametersAssignable = false;
//				}
//				if (parametersAssignable) {
//					return true;
//				}
//			}
//		}
//
//		// eg this=T other=Ljava/lang/Object;
//		if (isTypeVariableReference() && !other.isTypeVariableReference()) {
//			TypeVariable aVar = ((TypeVariableReference) this)
//					.getTypeVariable();
//			return aVar.resolve(world).canBeBoundTo(other);
//		}
//
//		if (other.isTypeVariableReference()) {
//			TypeVariableReferenceType otherType = (TypeVariableReferenceType) other;
//			if (this instanceof TypeVariableReference) {
//				return ((TypeVariableReference) this)
//						.getTypeVariable()
//						.resolve(world)
//						.canBeBoundTo(
//								otherType.getTypeVariable().getFirstBound()
//										.resolve(world));// pr171952
//				// return
//				// ((TypeVariableReference)this).getTypeVariable()==otherType
//				// .getTypeVariable();
//			} else {
//				// FIXME asc should this say canBeBoundTo??
//				return this.isAssignableFrom(otherType.getTypeVariable()
//						.getFirstBound().resolve(world));
//			}
//		}

		Type[] interfaces = other.getInterfaces();
		for (Type intface : interfaces) {
			boolean b;
//			if (thisRaw && intface.isParameterizedOrGenericType()) {
//				b = this.isAssignableFrom(intface.getRawType(), allowMissing);
//			} else {
			b = this.isAssignableFrom(intface);
//			}
			if (b) {
				return true;
			}
		}
		Type superclass = other.getSuperclass();
		if (superclass != null) {
			boolean b;
//			if (thisRaw && superclass.isParameterizedOrGenericType()) {
//				b = this.isAssignableFrom(superclass.getRawType(), allowMissing);
//			} else {
			b = this.isAssignableFrom(superclass);
//			}
			if (b) {
				return true;
			}
		}
		return false;
	}

	private boolean isPrimitiveType() {
		return false;
	}

	public boolean isInterface() {
		return dimensions > 0 ? false : Modifier.isInterface(node.access);
	}

	public Map<String, String> getAnnotationValuesInHierarchy(String LdescriptorLookingFor) {
		if (dimensions > 0) {
			return Collections.emptyMap();
		}
		Map<String, String> collector = new HashMap<>();
		getAnnotationValuesInHierarchy(LdescriptorLookingFor, new ArrayList<>(), collector);
		return collector;
	}

	public void getAnnotationValuesInHierarchy(String lookingFor, List<String> seen, Map<String, String> collector) {
		if (dimensions > 0) {
			return;
		}
		if (node.visibleAnnotations != null) {
			for (AnnotationNode anno : node.visibleAnnotations) {
				if (seen.contains(anno.desc))
					continue;
				seen.add(anno.desc);
				// System.out.println("Comparing "+anno.desc+" with "+lookingFor);
				if (anno.desc.equals(lookingFor)) {
					List<Object> os = anno.values;
					for (int i = 0; i < os.size(); i += 2) {
						collector.put(os.get(i).toString(), os.get(i + 1).toString());
					}
				}
				try {
					Type resolve = typeSystem.Lresolve(anno.desc);
					resolve.getAnnotationValuesInHierarchy(lookingFor, seen, collector);
				} catch (MissingTypeException mte) {
					// not on classpath, that's ok
				}
			}
		}
	}

	public boolean hasAnnotationInHierarchy(String lookingFor) {
		if (dimensions > 0) {
			return false;
		}
		return hasAnnotationInHierarchy(lookingFor, new ArrayList<String>());
	}

	public boolean hasAnnotationInHierarchy(String lookingFor, List<String> seen) {
		if (dimensions > 0) {
			return false;
		}
		if (node.visibleAnnotations != null) {
			for (AnnotationNode anno : node.visibleAnnotations) {
				if (seen.contains(anno.desc))
					continue;
				seen.add(anno.desc);
				// System.out.println("Comparing "+anno.desc+" with "+lookingFor);
				if (anno.desc.equals(lookingFor)) {
					return true;
				}
				try {
					Type resolve = typeSystem.Lresolve(anno.desc);
					if (resolve.hasAnnotationInHierarchy(lookingFor, seen)) {
						return true;
					}
				} catch (MissingTypeException mte) {
					// not on classpath, that's ok
				}
			}
		}
		return false;
	}

	public boolean isCondition() {
		if (dimensions > 0) {
			return false;
		}
		try {
			return implementsInterface(fromLdescriptorToSlashed(Condition));
		} catch (MissingTypeException mte) {
			return false;
		}
	}

	private boolean isEventListener() {
		try {
			return implementsInterface("org/springframework/context/event/EventListenerFactory");
		} catch (MissingTypeException mte) {
			return false;
		}

	}

	public boolean isAtConfiguration() {
		if (dimensions > 0) {
			return false;
		}
		return isMetaAnnotated(fromLdescriptorToSlashed(AtConfiguration), new HashSet<>());
	}

	public boolean isAbstractNestedCondition() {
		if (dimensions > 0) {
			return false;
		}
		return extendsClass("Lorg/springframework/boot/autoconfigure/condition/AbstractNestedCondition;");
	}

	public boolean isMetaAnnotated(String slashedTypeDescriptor) {
		if (dimensions > 0) {
			return false;
		}
		return isMetaAnnotated(slashedTypeDescriptor, new HashSet<>());
	}

	private boolean isMetaAnnotated(String slashedTypeDescriptor, Set<String> seen) {
		if (dimensions > 0) {
			return false;
		}
		for (Type t : this.getAnnotations()) {
			String tname = t.getName();
			if (tname.equals(slashedTypeDescriptor)) {
				return true;
			}
			if (!seen.contains(tname)) {
				seen.add(tname);
				if (t.isMetaAnnotated(slashedTypeDescriptor, seen)) {
					return true;
				}
			}
		}
		return false;
	}

	List<Type> annotations = null;

	public static final List<Type> NO_ANNOTATIONS = Collections.emptyList();

	public List<String> findConditionalOnMissingBeanValue() {
		if (dimensions > 0) {
			return Collections.emptyList();
		}
		List<String> findAnnotationValue = findAnnotationValue(AtConditionalOnMissingBean, false);
		if (findAnnotationValue == null) {
			if (node.visibleAnnotations != null) {
				for (AnnotationNode an : node.visibleAnnotations) {
					if (an.desc.equals(AtConditionalOnMissingBean)) {
						System.out.println("??? found nothing on this @COC annotated thing " + this.getName());
					}
				}
			}
		}
		return findAnnotationValue;
	}

	public List<String> findConditionalOnClassValue() {
		if (dimensions > 0) {
			return Collections.emptyList();
		}
		List<String> findAnnotationValue = findAnnotationValue(AtConditionalOnClass, false);
		if (findAnnotationValue == null) {
			if (node.visibleAnnotations != null) {
				for (AnnotationNode an : node.visibleAnnotations) {
					if (an.desc.equals(AtConditionalOnClass)) {
						System.out.println("??? found nothing on this @COC annotated thing " + this.getName());
					}
				}
			}
		}
		return findAnnotationValue;
	}

	public List<String> findEnableConfigurationPropertiesValue() {
		if (dimensions > 0) {
			return Collections.emptyList();
		}
		List<String> values = findAnnotationValue(AtEnableConfigurationProperties, false);
		return values;
	}

	public Map<String, List<String>> findImports() {
		if (dimensions > 0) {
			return Collections.emptyMap();
		}
		return findAnnotationValueWithHostAnnotation(AtImports, true, new HashSet<>());
	}

	public List<String> findAnnotationValue(String annotationType, boolean searchMeta) {
		if (dimensions > 0) {
			return Collections.emptyList();
		}
		return findAnnotationValue(annotationType, searchMeta, new HashSet<>());
	}

	@SuppressWarnings("unchecked")
	public Map<String, List<String>> findAnnotationValueWithHostAnnotation(String annotationType, boolean searchMeta,
			Set<String> visited) {
		if (dimensions > 0) {
			return Collections.emptyMap();
		}
		if (!visited.add(this.getName())) {
			return Collections.emptyMap();
		}
		Map<String, List<String>> collectedResults = new LinkedHashMap<>();
		if (node.visibleAnnotations != null) {
			for (AnnotationNode an : node.visibleAnnotations) {
				if (an.desc.equals(annotationType)) {
					List<Object> values = an.values;
					if (values != null) {
						for (int i = 0; i < values.size(); i += 2) {
							if (values.get(i).equals("value")) {
								List<String> importedReferences = ((List<org.objectweb.asm.Type>) values.get(i + 1))
										.stream().map(t -> t.getDescriptor()).collect(Collectors.toList());
								collectedResults.put(this.getName().replace("/", "."), importedReferences);
							}
						}
					}
				}
			}
			if (searchMeta) {
				for (AnnotationNode an : node.visibleAnnotations) {
					// For example @EnableSomething might have @Import on it
					Type annoType = null;
					try {
						annoType = typeSystem.Lresolve(an.desc);
					} catch (MissingTypeException mte) {
						System.out.println("SBG: WARNING: Unable to find " + an.desc + " skipping...");
						continue;
					}
					collectedResults.putAll(
							annoType.findAnnotationValueWithHostAnnotation(annotationType, searchMeta, visited));
				}
			}
		}
		return collectedResults;
	}

	@SuppressWarnings("unchecked")
	public List<String> findAnnotationValue(String annotationType, boolean searchMeta, Set<String> visited) {
		if (dimensions > 0) {
			return Collections.emptyList();
		}
		if (!visited.add(this.getName())) {
			return Collections.emptyList();
		}
		List<String> collectedResults = new ArrayList<>();
		if (node.visibleAnnotations != null) {
			for (AnnotationNode an : node.visibleAnnotations) {
				if (an.desc.equals(annotationType)) {
					List<Object> values = an.values;
					if (values != null) {
						for (int i = 0; i < values.size(); i += 2) {
							if (values.get(i).equals("value")) {
								return ((List<org.objectweb.asm.Type>) values.get(i + 1)).stream()
										.map(t -> t.getDescriptor())
										.collect(Collectors.toCollection(() -> collectedResults));
							}
						}
					}
				}
			}
			if (searchMeta) {
				for (AnnotationNode an : node.visibleAnnotations) {
					// For example @EnableSomething might have @Import on it
					Type annoType = typeSystem.Lresolve(an.desc);
					collectedResults.addAll(annoType.findAnnotationValue(annotationType, searchMeta, visited));
				}
			}
		}
		return collectedResults;
	}

	private List<Type> getAnnotations() {
		if (dimensions > 0) {
			return Collections.emptyList();
		}
		if (annotations == null) {
			annotations = new ArrayList<>();
			if (node.visibleAnnotations != null) {
				for (AnnotationNode an : node.visibleAnnotations) {
					try {
						annotations.add(this.typeSystem.Lresolve(an.desc));
					} catch (MissingTypeException mte) {
						// that's ok you weren't relying on it anyway!
					}
				}
			}
//			if (node.invisibleAnnotations != null) {
//			for (AnnotationNode an: node.invisibleAnnotations) {
//				try {
//					annotations.add(this.typeSystem.Lresolve(an.desc));
//				} catch (MissingTypeException mte) {
//					// that's ok you weren't relying on it anyway!
//				}
//			}
//			}
			if (annotations.size() == 0) {
				annotations = NO_ANNOTATIONS;
			}
		}
		return annotations;
	}

	public Type findAnnotation(Type searchType) {
		if (dimensions > 0) {
			return null;
		}
		List<Type> annos = getAnnotations();
		for (Type anno : annos) {
			if (anno.equals(searchType)) {
				return anno;
			}
		}
		return null;
	}

	/**
	 * Discover any uses of @Indexed or javax annotations, via direct/meta or
	 * interface usage. Example output:
	 * 
	 * <pre>
	 * <code>
	 * org.springframework.samples.petclinic.PetClinicApplication=org.springframework.stereotype.Component
	 * org.springframework.samples.petclinic.model.Person=javax.persistence.MappedSuperclass
	 * org.springframework.samples.petclinic.owner.JpaOwnerRepositoryImpl=[org.springframework.stereotype.Component,javax.transaction.Transactional]
	 * </code>
	 * </pre>
	 */
	public Entry<Type, List<Type>> getRelevantStereotypes() {
		if (dimensions > 0)
			return null;
		List<Type> relevantAnnotations = new ArrayList<>();
		List<Type> indexedTypesInHierachy = getAnnotatedElementsInHierarchy(
				a -> a.desc.equals("Lorg/springframework/stereotype/Indexed;"));
		relevantAnnotations.addAll(indexedTypesInHierachy);
		List<Type> types = getJavaxAnnotationsInHierarchy();
		relevantAnnotations.addAll(types);
		if (!relevantAnnotations.isEmpty()) {
			return new AbstractMap.SimpleImmutableEntry<Type, List<Type>>(this, relevantAnnotations);
		} else {
			return null;
		}
	}

	/**
	 * Find usage of javax annotations in hierarchy (including meta usage).
	 * 
	 * @return list of types in the hierarchy of this type that are affected by a
	 *         javax annotation
	 */
	List<Type> getJavaxAnnotationsInHierarchy() {
		return getJavaxAnnotationsInHierarchy(new HashSet<>());
	}

	private boolean isAnnotated(String Ldescriptor) {
		if (dimensions > 0) {
			return false;
		}
		if (node.visibleAnnotations != null) {
			for (AnnotationNode an : node.visibleAnnotations) {
				if (an.desc.equals(Ldescriptor)) {
					return true;
				}
			}
		}
		return false;
	}

	// TODO this is broken, don't use!
	public boolean isAnnotated(String Ldescriptor, boolean checkMetaUsage) {
		if (dimensions > 0) {
			return false;
		}
		if (checkMetaUsage) {
			return isMetaAnnotated(Ldescriptor);
		}
		if (node.visibleAnnotations != null) {
			for (AnnotationNode an : node.visibleAnnotations) {
				if (an.desc.equals(Ldescriptor)) {
					return true;
				}
			}
		}
		return false;
	}

	private List<Type> getAnnotatedElementsInHierarchy(Predicate<AnnotationNode> p) {
		return getAnnotatedElementsInHierarchy(p, new HashSet<>());
	}

	private List<Type> getAnnotatedElementsInHierarchy(Predicate<AnnotationNode> p, Set<String> seen) {
		if (dimensions > 0)
			return Collections.emptyList();
		List<Type> results = new ArrayList<>();
		if (node.visibleAnnotations != null) {
			for (AnnotationNode an : node.visibleAnnotations) {
				if (seen.add(an.desc)) {
					if (p.test(an)) {
						results.add(this);
					} else {
						Type annoType = typeSystem.Lresolve(an.desc, true);
						if (annoType != null) {
							List<Type> ts = annoType.getAnnotatedElementsInHierarchy(p, seen);
							results.addAll(ts);
						}
					}
				}
			}
		}
		return results.size() > 0 ? results : Collections.emptyList();
	}

	private List<Type> getJavaxAnnotationsInHierarchy(Set<String> seen) {
		if (dimensions > 0)
			return Collections.emptyList();
		List<Type> result = new ArrayList<>();
		if (node.visibleAnnotations != null) {
			for (AnnotationNode an : node.visibleAnnotations) {
				if (seen.add(an.desc)) {
					Type annoType = typeSystem.Lresolve(an.desc, true);
					if (annoType != null) {
						if (annoType.getDottedName().startsWith("javax.")) {
							result.add(annoType);
						} else {
							List<Type> ts = annoType.getJavaxAnnotationsInHierarchy(seen);
							result.addAll(ts);
						}
					}
				}
			}
		}
		Type[] intfaces = getInterfaces();
		for (Type intface : intfaces) {
			if (seen.add(intface.getDottedName())) {
				result.addAll(intface.getJavaxAnnotationsInHierarchy(seen));
			}
		}
		return result;
	}

	public List<Type> getNestedTypes() {
		if (dimensions > 0)
			return Collections.emptyList();
		List<Type> result = null;
		List<InnerClassNode> innerClasses = node.innerClasses;
		for (InnerClassNode inner : innerClasses) {
			if (inner.outerName == null || !inner.outerName.equals(getName())) {
//				System.out.println("SKIPPING "+inner.name+" because outer is "+inner.outerName+" and we are looking at "+getName());
				continue;
			}
			if (inner.name.equals(getName())) {
				continue;
			}
			Type t = typeSystem.resolve(inner.name); // aaa/bbb/ccc/Ddd$Eee
			if (result == null) {
				result = new ArrayList<>();
			}
			result.add(t);
		}
		return result == null ? Collections.emptyList() : result;
	}

	public String getDescriptor() {
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < dimensions; i++) {
			s.append("[");
		}
		s.append("L").append(node.name.replace(".", "/")).append(";");
		return s.toString();
	}

	/**
	 * Find compilation hints directly on this type or used as a meta-annotation on
	 * annotations on this type.
	 */
	public List<Hint> getHints() {
		if (dimensions > 0) {
			return Collections.emptyList();
		}
		List<Hint> hints = new ArrayList<>();
		List<CompilationHint> hintx = typeSystem.findHints(getName());
		if (hintx.size() != 0) {
			List<Type> s = new ArrayList<>();
			s.add(this);
			for (CompilationHint hintxx : hintx) {
				hints.add(new Hint(s, hintxx.skipIfTypesMissing, hintxx.follow, hintxx.getDependantTypes(),
						Collections.emptyMap()));
			}
		}
		if (node.visibleAnnotations != null) {
			for (AnnotationNode an : node.visibleAnnotations) {
				Type annotationType = typeSystem.Lresolve(an.desc, true);
				if (annotationType == null) {
					SpringFeature.log("Couldn't resolve " + an.desc + " annotation type whilst searching for hints on "
							+ getName());
				} else {
					Stack<Type> s = new Stack<>();
					s.push(this);
					annotationType.collectHints(an, hints, new HashSet<>(), s);
				}
			}
		}
		if (isImportSelector() && hints.size() == 0) {
			// Failing early as this will likely result in a problem later - fix is
			// typically (right now) to add a new Hint in the configuration module
			if (ConfigOptions.areMissingSelectorHintsAnError()) {
				throw new IllegalStateException("No access hint found for import selector: " + getDottedName());
			} else {
				System.out.println("WARNING: No access hint found for import selector: " + getDottedName());
			}
		}
		return hints.size() == 0 ? Collections.emptyList() : hints;
	}

	// TODO handle repeatable annotations everywhere!

	void collectHints(AnnotationNode an, List<Hint> hints, Set<AnnotationNode> visited, Stack<Type> annotationChain) {
		if (!visited.add(an)) {
			return;
		}
		try {
			annotationChain.push(this);
			// Am I a compilation hint?
			List<CompilationHint> hints2 = typeSystem.findHints(an.desc);// ;SpringConfiguration.findProposedHints(an.desc);
			if (hints2.size() != 0) {
				List<String> typesCollectedFromAnnotation = collectTypes(an);
				if (an.desc.equals(Type.AtEnableConfigurationProperties)) {
					// TODO special handling here for @EnableConfigurationProperties - should we promote this to a hint annotation value or truly a special case?
					addInners(typesCollectedFromAnnotation);
				}
				for (CompilationHint hints2a : hints2) {
					hints.add(new Hint(new ArrayList<>(annotationChain), hints2a.skipIfTypesMissing, hints2a.follow,
							hints2a.getDependantTypes(),
							asMap(typesCollectedFromAnnotation, hints2a.skipIfTypesMissing)));
				}
			}
			// check for meta annotation
			if (node.visibleAnnotations != null) {
				for (AnnotationNode an2 : node.visibleAnnotations) {
					Type annotationType = typeSystem.Lresolve(an2.desc, true);
					if (annotationType == null) {
						SpringFeature.log("Couldn't resolve " + an2.desc
								+ " annotation type whilst searching for hints on " + getName());
					} else {
						annotationType.collectHints(an2, hints, visited, annotationChain);
					}
				}
			}
		} finally {
			annotationChain.pop();
		}
	}

	private void addInners(List<String> propertiesTypes) {
		List<String> extras = new ArrayList<>();
		for (String propertiesType: propertiesTypes) {
			Type type = typeSystem.Lresolve(propertiesType,true);
			if (type !=null) {
				// TODO recurse all the way down
				List<Type> nestedTypes = type.getNestedTypes();
				for (Type nestedType: nestedTypes) {
					extras.add(nestedType.getDescriptor());
				}
			}
		}
		propertiesTypes.addAll(extras);
	}

	private Map<String, Integer> asMap(List<String> typesCollectedFromAnnotation, boolean usingForVisibilityCheck) {
		Map<String, Integer> map = new HashMap<>();
		for (String t : typesCollectedFromAnnotation) {
			Type type = typeSystem.Lresolve(t, true);
			int ar = -1;
			if (usingForVisibilityCheck) {
				ar = AccessBits.CLASS;// TypeKind.EXISTENCE_CHECK;
			} else {
				if (type != null && (type.isCondition() || type.isEventListener())) {
					ar = AccessBits.RESOURCE | AccessBits.CLASS | AccessBits.DECLARED_CONSTRUCTORS;// TypeKind.RESOURCE_AND_INSTANTIATION;//AccessRequired.RESOURCE_CTORS_ONLY;
					if (type.isAbstractNestedCondition()) {
						// Need to pull in member types of this condition
						// Type[] memberTypes = type.getMemberTypes();
						// for (Type memberType: memberTypes) {
						// // map.put(memberType.getDottedName(), AccessRequired.RESOURCE_ONLY);
						// }
					}
				} else {
					ar = AccessBits.EVERYTHING;// TypeKind.EVERYTHING;
				}
			}
			map.put(fromLdescriptorToDotted(t), ar);
		}
		return map;
	}

	private Type[] getMemberTypes() {
		if (dimensions > 0)
			return new Type[0];
		List<Type> result = new ArrayList<>();
		List<InnerClassNode> nestMembers = node.innerClasses;
		if (nestMembers != null) {
			for (InnerClassNode icn : nestMembers) {
				if (icn.name.startsWith(this.getName() + "$")) {
					result.add(typeSystem.resolveSlashed(icn.name));
				}
			}
			System.out.println(this.getName()
					+ " has inners " + nestMembers.stream().map(f -> "oo=" + this.getDescriptor() + "::o=" + f.outerName
							+ "::n=" + f.name + "::in=" + f.innerName).collect(Collectors.joining(","))
					+ "  >> " + result);
		}
		return result.toArray(new Type[0]);
	}

	private List<CompilationHint> findCompilationHintHelper(HashSet<Type> visited) {
		if (!visited.add(this)) {
			return null;
		}
		if (node.visibleAnnotations != null) {
			for (AnnotationNode an : node.visibleAnnotations) {
				List<CompilationHint> compilationHints = typeSystem.findHints(an.desc);// SpringConfiguration.findProposedHints(an.desc);
				if (compilationHints.size() != 0) {
					return compilationHints;
				}
				Type resolvedAnnotation = typeSystem.Lresolve(an.desc);
				compilationHints = resolvedAnnotation.findCompilationHintHelper(visited);
				if (compilationHints.size() != 0) {
					return compilationHints;
				}
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private List<String> collectTypes(AnnotationNode an) {
		List<Object> values = an.values;
		if (values != null) {
			for (int i = 0; i < values.size(); i += 2) {
				if (values.get(i).equals("value")) {
					// For some annotations it is a list, for some a single class (e.g.
					// @ConditionalOnSingleCandidate)
					Object object = values.get(i + 1);
					List<String> importedReferences = null;
					if (object instanceof List) {
						importedReferences = ((List<org.objectweb.asm.Type>) object).stream()
								.map(t -> t.getDescriptor()).collect(Collectors.toList());
					} else {
						importedReferences = new ArrayList<>();
						importedReferences.add(((org.objectweb.asm.Type) object).getDescriptor());
					}
					return importedReferences;
				}
			}
		}
		return Collections.emptyList();
	}

	public boolean isImportSelector() {
		return implementsInterface(fromLdescriptorToSlashed(ImportSelector));
	}

	public boolean isImportRegistrar() {
		return implementsInterface(fromLdescriptorToSlashed(ImportBeanDefinitionRegistrar));
	}

	public static String fromLdescriptorToSlashed(String Ldescriptor) {
		int dims = 0;
		int p = 0;
		if (Ldescriptor.startsWith("[")) {
			while (Ldescriptor.charAt(p) == '[') {
				p++;
				dims++;
			}
		}
		StringBuilder r = new StringBuilder();
		r.append(Ldescriptor.substring(p + 1, Ldescriptor.length() - 1));
		for (int i = 0; i < dims; i++) {
			r.append("[]");
		}
		return r.toString();
	}

	public static String fromLdescriptorToDotted(String Ldescriptor) {
		return fromLdescriptorToSlashed(Ldescriptor).replace("/", ".");
	}

	private List<CompilationHint> findCompilationHint(Type annotationType) {
		String descriptor = "L" + annotationType.getName().replace(".", "/") + ";";
		List<CompilationHint> hints = typeSystem.findHints(descriptor);// SpringConfiguration.findProposedHints(descriptor);
		if (hints.size() != 0) {
			return hints;
		} else {
			// check for meta annotation
			return annotationType.findCompilationHintHelper(new HashSet<>());
		}
	}

	public void collectMissingAnnotationTypesHelper(Set<String> missingAnnotationTypes, HashSet<Type> visited) {
		if (dimensions > 0)
			return;
		if (!visited.add(this)) {
			return;
		}
		if (node.visibleAnnotations != null) {
			for (AnnotationNode an : node.visibleAnnotations) {
				Type annotationType = typeSystem.Lresolve(an.desc, true);
				if (annotationType == null) {
					missingAnnotationTypes.add(an.desc.substring(0, an.desc.length() - 1).replace("/", "."));
				} else {
					annotationType.collectMissingAnnotationTypesHelper(missingAnnotationTypes, visited);
				}
			}
		}
	}

	public int getMethodCount() {
		return node.methods.size();
	}

	public boolean isAnnotation() {
		if (dimensions > 0)
			return false;
		return (node.access & Opcodes.ACC_ANNOTATION) != 0;
	}

	@SuppressWarnings("unchecked")
	public List<Type> getAutoConfigureBeforeOrAfter() {
		if (dimensions > 0)
			return Collections.emptyList();
		List<Type> result = new ArrayList<>();
		for (AnnotationNode an : node.visibleAnnotations) {
			if (an.desc.equals("Lorg/springframework/boot/autoconfigure/AutoConfigureAfter;")) {
				List<Object> values = an.values;
				if (values != null) {
					for (int i = 0; i < values.size(); i += 2) {
						if (values.get(i).equals("value")) {
							List<org.objectweb.asm.Type> types = (List<org.objectweb.asm.Type>) values.get(i + 1);
							for (org.objectweb.asm.Type type : types) {
								Type t = typeSystem.Lresolve(type.getDescriptor());
								if (t != null) {
									result.add(t);
								}
							}
						}
					}
				}
			}
		}
		return result;
	}

	public boolean hasOnlySimpleConstructor() {
		if (dimensions > 0)
			return false;
		boolean hasCtor = false;
		List<MethodNode> methods = node.methods;
		for (MethodNode mn : methods) {
			if (mn.name.equals("<init>")) {
				if (mn.desc.equals("()V")) {
					hasCtor = true;
				} else {
					return false;
				}
			}
		}
		return hasCtor;
	}

	public static boolean shouldBeProcessed(String key, TypeSystem ts) {
		String[] guardTypes = SpringConfiguration.findProposedFactoryGuards(key);// .get(key);
		if (guardTypes == null) {
			return true;
		} else {
			for (String guardType : guardTypes) {
				Type resolvedType = ts.resolveDotted(guardType, true);
				if (resolvedType != null) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * Find the @ConfigurationHint annotations on this type (may be more than one)
	 * and from them build CompilationHints, taking care to convert class references
	 * to strings because they may not be resolvable. TODO ok to discard those that
	 * aren't resolvable at this point?
	 * 
	 * @return
	 */
	public List<CompilationHint> unpackConfigurationHints() {
		if (dimensions > 0)
			return Collections.emptyList();
		List<CompilationHint> hints = null;
		if (node.visibleAnnotations != null) {
			for (AnnotationNode an : node.visibleAnnotations) {
				if (fromLdescriptorToDotted(an.desc).equals(NativeImageHint.class.getName())) {
					CompilationHint hint = fromConfigurationHintToCompilationHint(an);
					if (hints == null) {
						hints = new ArrayList<>();
					}
					hints.add(hint);
				} else if (fromLdescriptorToDotted(an.desc).equals(NativeImageHints.class.getName())) {
					List<CompilationHint> chints = fromConfigurationHintsToCompilationHints(an);
					if (hints == null) {
						hints = new ArrayList<>();
					}
					hints.addAll(chints);
				}
			}
		}
		// TODO support repeatable version
		return hints == null ? Collections.emptyList() : hints;
	}

	@SuppressWarnings("unchecked")
	private CompilationHint fromConfigurationHintToCompilationHint(AnnotationNode an) {
		CompilationHint ch = new CompilationHint();
		List<Object> values = an.values;
		if (values != null) {
			for (int i = 0; i < values.size(); i += 2) {
				String key = (String) values.get(i);
				Object value = values.get(i + 1);
				if (key.equals("trigger")) {
					// value(String)=Ljava/lang/String;(org.objectweb.asm.Type)
					ch.setTargetType(((org.objectweb.asm.Type) value).getClassName());
					/*
					 * } else if (key.equals("types")) { // types=[Ljava/lang/String;,
					 * Ljava/lang/Float;](class java.util.ArrayList)
					 * ArrayList<org.objectweb.asm.Type> types =
					 * (ArrayList<org.objectweb.asm.Type>)value; for (org.objectweb.asm.Type type:
					 * types) { ch.addDependantType(type.getClassName(), inferTypeKind(type)); }
					 */
				} else if (key.equals("typeInfos")) {
					List<AnnotationNode> typeInfos = (List<AnnotationNode>) value;
					for (AnnotationNode typeInfo : typeInfos) {
						unpackTypeInfo(typeInfo, ch);
					}
				} else if (key.equals("abortIfTypesMissing")) {
					Boolean b = (Boolean) value;
					ch.setAbortIfTypesMissing(b);
				} else if (key.equals("follow")) {
					Boolean b = (Boolean) value;
					ch.setFollow(b);
				} else {
					System.out.println("annotation " + key + "=" + value + "(" + value.getClass() + ")");
				}
			}
		}
		if (ch.getTargetType() == null) {
			ch.setTargetType("java.lang.Object");// TODO should be set from annotation default value, not duplicated
													// here
		}
		return ch;
	}

	@SuppressWarnings("unchecked")
	private void unpackTypeInfo(AnnotationNode typeInfo, CompilationHint ch) {
		List<Object> values = typeInfo.values;
		List<org.objectweb.asm.Type> types = new ArrayList<>();
		List<String> typeNames = new ArrayList<>();
		int accessRequired = -1;
		for (int i = 0; i < values.size(); i += 2) {
			String key = (String) values.get(i);
			Object value = values.get(i + 1);
			if (key.equals("types")) {
				types = (ArrayList<org.objectweb.asm.Type>) value;
			} else if (key.equals("access")) {
				accessRequired = (Integer) value;
			} else if (key.equals("typeNames")) {
				typeNames = (ArrayList<String>) value;
			}
		}
		for (org.objectweb.asm.Type type : types) {
			ch.addDependantType(type.getClassName(), accessRequired == -1 ? inferTypeKind(type) : accessRequired);
		}
		for (String typeName : typeNames) {
			Type resolvedType = typeSystem.resolveName(typeName, true);
			if (resolvedType != null) {
				ch.addDependantType(typeName, accessRequired == -1 ? inferTypeKind(resolvedType) : accessRequired);
			}

		}
	}

	private int inferTypeKind(Type t) {
		if (t == null) {
			return AccessBits.ALL;
		}
		if (t.isAtConfiguration()) {
			return AccessBits.CONFIGURATION;
		} else {
			return AccessBits.ALL; // TODO this is wrong!
		}
	}

	@SuppressWarnings("unchecked")
	private List<CompilationHint> fromConfigurationHintsToCompilationHints(AnnotationNode an) {
		List<CompilationHint> chs = new ArrayList<>();
		List<Object> values = an.values;
		for (int i = 0; i < values.size(); i += 2) {
			String key = (String) values.get(i);
			Object value = values.get(i + 1);
			if (key.equals("value")) {
				// value=[org.objectweb.asm.tree.AnnotationNode@63e31ee,
				// org.objectweb.asm.tree.AnnotationNode@68fb2c38]
				List<AnnotationNode> annotationNodes = (List<AnnotationNode>) value;
				for (int j = 0; j < annotationNodes.size(); j++) {
					chs.add(fromConfigurationHintToCompilationHint(annotationNodes.get(j)));
				}
			}
		}
		return chs;
	}

	private int inferTypeKind(org.objectweb.asm.Type type) {
		Type t = typeSystem.resolve(type, true);
		if (t == null) {
			// TODO All because of type might be array and we aren't resolving that quite
			// right yet
			return AccessBits.ALL;
		}
		if (t.isAtConfiguration()) {
			return AccessBits.CONFIGURATION;
		} else {
			return AccessBits.ALL; // TODO this is wrong!
		}
	}

	public List<CompilationHint> getCompilationHints() {
		if (dimensions > 0)
			return Collections.emptyList();
		return unpackConfigurationHints();
	}

	public int getDimensions() {
		return dimensions;
	}

	public boolean isArray() {
		return dimensions > 0;
	}

	/**
	 * @return true if type or a method inside is marked @Transactional
	 */
	public boolean isTransactional() {
		// TODO are these usable as meta annotations?
		return isAnnotated(AtTransactional) || isAnnotated(AtJavaxTransactional);
	}

	public boolean hasTransactionalMethods() {
		// TODO meta annotation usage?
		List<Method> methodsWithAtTransactional = getMethodsWithAnnotation(AtTransactional);
		if (methodsWithAtTransactional.size() > 0) {
			return true;
		}
		List<Method> methodsWithAtJavaxTransactional = getMethodsWithAnnotation(AtJavaxTransactional);
		if (methodsWithAtJavaxTransactional.size() > 0) {
			return true;
		}
		return false;
	}

	public boolean isAnnotatedInHierarchy(String anno) {
		if (isAnnotated(AtTransactional)) {
			return true;
		}
		List<Method> methodsWithAnnotation = getMethodsWithAnnotation(anno);
		if (methodsWithAnnotation.size() != 0) {
			return true;
		}
		Type superclass = this.getSuperclass();
		if (superclass != null) {
			if (superclass.isAnnotatedInHierarchy(anno)) {
				return true;
			}
		}
		Type[] intfaces = this.getInterfaces();
		if (intfaces != null) {
			for (Type intface : intfaces) {
				if (intface.isAnnotatedInHierarchy(anno)) {
					return true;
				}
			}
		}
		return false;
	}

	public List<String> collectTypeParameterNames() {
		// TODO Auto-generated method stub
		return null;
	}

	public boolean isAtRepository() {
		return isAnnotated(AtRepository);
	}

	public boolean isAtEnableBatchProcessing() {
		return isAnnotated(AtEnableBatchProcessing);
	}

	public boolean isAtResponseBody() {
		boolean b = hasAnnotation(AtResponseBody, true);
		// System.out.println("Checking if " + getName() + " is @ResponseBody meta annotated: " + b);
		return b;
	}

	public Collection<Type> collectAtMappingMarkedReturnTypes() {
		Set<Type> returnTypes = new LinkedHashSet<>();
		List<Method> methodsWithAnnotation = getMethodsWithAnnotation(AtMapping, true);
		for (Method m : methodsWithAnnotation) {
			returnTypes.add(m.getReturnType());
		}
		return returnTypes;
	}

}