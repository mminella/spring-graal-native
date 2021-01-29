/*
 * Copyright 2021-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.batch.core.configuration.annotation;

import java.util.ArrayList;
import java.util.List;

import org.springframework.nativex.extension.ComponentProcessor;
import org.springframework.nativex.extension.NativeImageContext;
import org.springframework.nativex.type.Method;
import org.springframework.nativex.type.Type;

/**
 * @author Michael Minella
 */
public class BatchScopeComponentProcessor implements ComponentProcessor {

	@Override
	public boolean handle(NativeImageContext imageContext, String componentType, List<String> classifiers) {
		System.out.println(">> BatchScopeComponentProcessor.handle " + componentType);
		Type type = imageContext.getTypeSystem().resolveName(componentType);
		boolean isInteresting =  (type != null && (type.isBatchScoped() || type.hasBatchScopedMethods()));
		System.out.println(">> is interesting? " + isInteresting);
		return isInteresting;
	}

	@Override
	public void process(NativeImageContext imageContext, String componentType, List<String> classifiers) {
		System.out.println("***************************************************************************");
		System.out.println(">> BatchScopeComponentProcessor.process " + componentType);
		System.out.println("***************************************************************************");
		Type type = imageContext.getTypeSystem().resolveName(componentType);
//		List<String> scopedInterfaces = new ArrayList<>();
//		for (Type intface: type.getInterfaces()) {
//			scopedInterfaces.add(intface.getDottedName());
//		}
//		if (scopedInterfaces.size()==0) {
//			imageContext.log("BatchScopeComponentProcessor: unable to find interfaces to proxy on "+componentType);
//			System.out.println(">> BatchScopeComponentProcessor.process not done");
//			return;
//		}
//		scopedInterfaces.add("org.springframework.aop.SpringProxy");
//		scopedInterfaces.add("org.springframework.aop.framework.Advised");
//		scopedInterfaces.add("org.springframework.core.DecoratingProxy");
//		imageContext.addProxy(scopedInterfaces);
//		imageContext.log("BatchScopeComponentProcessor: creating proxy for these interfaces: "+scopedInterfaces);

		List<Method> methods = type.getMethods();

		for (Method method : methods) {
			System.out.println("\t==================================================================");
			System.out.println("\t>> method: " + method.toString());
			System.out.println("\t==================================================================");
			List<Type> annotationTypes = method.getAnnotationTypes();


			for (Type annotationType : annotationTypes) {
				System.out.println("\t>> is method batch scoped? ");
				if(annotationType.getDescriptor().equals(Type.AtJobScope) ||
						annotationType.getDescriptor().equals(Type.AtStepScope)) {
					System.out.println("\t>> method:" + method + " IS BATCH SCOPED!!!");

					Type returnType = method.getReturnType();
					System.out.println("\t>> returnType = " + returnType);


					List<String> returnTypeScopedInterfaces = new ArrayList<>();

					if(returnType != null && returnType.getInterfaces() != null) {

						if(returnType.isInterface()) {
							returnTypeScopedInterfaces.add(returnType.getDottedName());
						}

						addInterfaces(returnType, returnTypeScopedInterfaces);
					}

					returnTypeScopedInterfaces.add("org.springframework.aop.SpringProxy");
					returnTypeScopedInterfaces.add("org.springframework.aop.framework.Advised");
					returnTypeScopedInterfaces.add("org.springframework.core.DecoratingProxy");
					imageContext.addProxy(returnTypeScopedInterfaces);
					imageContext.log("BatchScopeComponentProcessor: creating proxy for these interfaces in return types:" + returnTypeScopedInterfaces);

					System.out.println("\t>> Returned interfaces are: " );
					for (String scopedInterface : returnTypeScopedInterfaces) {
						System.out.println("\t" + scopedInterface);
					}
				}
			}
		}

		System.out.println(">> BatchScopeComponentProcessor.process done");
	}

	private void addInterfaces(Type type, List<String> scopedInterfaces) {
		for (Type intface: type.getInterfaces()) {

			if(intface.getInterfaces() != null) {
				addInterfaces(intface, scopedInterfaces);
			}

			scopedInterfaces.add(intface.getDottedName());
		}
	}
}
