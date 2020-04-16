/*
 * Copyright 2020 the original author or authors.
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
package org.springframework.batch;

import org.springframework.batch.core.configuration.annotation.BatchConfigurationSelector;
import org.springframework.batch.core.configuration.annotation.ModularBatchConfiguration;
import org.springframework.batch.core.configuration.annotation.SimpleBatchConfiguration;
import org.springframework.graal.extension.NativeImageConfiguration;
import org.springframework.graal.extension.NativeImageHint;
import org.springframework.graal.extension.TypeInfo;

@NativeImageHint(trigger= BatchConfigurationSelector.class, typeInfos = {
		@TypeInfo(types= {ModularBatchConfiguration.class, SimpleBatchConfiguration.class})
})
public class Hints implements NativeImageConfiguration {
}
