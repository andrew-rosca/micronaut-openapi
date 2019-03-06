/*
 * Copyright 2017-2019 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.http.client.convert;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.core.convert.TypeConverter;

import javax.inject.Singleton;

@Factory
public class TypeConverters {

    @Bean
    @Singleton
    public TypeConverter<String, Bar> stringToBarConverter() {
        return TypeConverter.of(
                String.class,
                Bar.class,
                Bar::new
        );
    }

    @Bean
    @Singleton
    public TypeConverter<String, Foo> stringToFooConverter() {
        return TypeConverter.of(
                String.class,
                Foo.class,
                Foo::new
        );
    }

}