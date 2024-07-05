/*
 * Copyright 2017-2023 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.openapi.visitor;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.uri.UriMatchTemplate;
import io.micronaut.http.uri.UriMatchVariable;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.visitor.VisitorContext;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.tags.Tag;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static io.micronaut.openapi.visitor.OpenApiApplicationVisitor.replacePlaceholders;
import static io.micronaut.openapi.visitor.OpenApiConfigProperty.MICRONAUT_SERVER_CONTEXT_PATH;
import static io.micronaut.openapi.visitor.OpenApiModelProp.PROP_NAME;
import static io.micronaut.openapi.visitor.OpenApiModelProp.PROP_SCOPES;
import static io.micronaut.openapi.visitor.StringUtil.CLOSE_BRACE;
import static io.micronaut.openapi.visitor.StringUtil.DOLLAR;
import static io.micronaut.openapi.visitor.StringUtil.OPEN_BRACE;
import static io.micronaut.openapi.visitor.StringUtil.SLASH;
import static io.micronaut.openapi.visitor.SchemaDefinitionUtils.toValue;

/**
 * Abstract base class for OpenAPI visitors.
 *
 * @author graemerocher
 * @since 1.0
 */
@Internal
abstract class AbstractOpenApiVisitor {

    private static final Lock VISITED_ELEMENTS_LOCK = new ReentrantLock();

    /**
     * Increments the number of visited elements.
     *
     * @param context The context
     */
    void incrementVisitedElements(VisitorContext context) {
        VISITED_ELEMENTS_LOCK.lock();
        try {
            ContextUtils.put(Utils.ATTR_VISITED_ELEMENTS, ContextUtils.getVisitedElements(context) + 1, context);
        } finally {
            VISITED_ELEMENTS_LOCK.unlock();
        }
    }

    /**
     * Returns the number of visited elements.
     *
     * @param context The context.
     *
     * @return The number of visited elements.
     */
    int visitedElements(VisitorContext context) {
        VISITED_ELEMENTS_LOCK.lock();
        try {
            return ContextUtils.getVisitedElements(context);
        } finally {
            VISITED_ELEMENTS_LOCK.unlock();
        }
    }

    /**
     * Reads the security requirements annotation of the specified element.
     *
     * @param element The Element to process.
     *
     * @return A list of SecurityRequirement
     */
    List<SecurityRequirement> readSecurityRequirements(Element element) {
        return readSecurityRequirements(element.getAnnotationValuesByType(io.swagger.v3.oas.annotations.security.SecurityRequirement.class));
    }

    List<SecurityRequirement> readSecurityRequirements(List<AnnotationValue<io.swagger.v3.oas.annotations.security.SecurityRequirement>> annotations) {
        var result = new ArrayList<SecurityRequirement>(annotations.size());
        for (var ann : annotations) {
            result.add(ConvertUtils.mapToSecurityRequirement(ann));
        }
        return result;
    }

    /**
     * Resolve the PathItem for the given {@link UriMatchTemplate}.
     *
     * @param context The context
     * @param matchTemplates The match templates
     *
     * @return The {@link PathItem}
     */
    Map<String, List<PathItem>> resolvePathItems(VisitorContext context, List<UriMatchTemplate> matchTemplates) {
        OpenAPI openAPI = Utils.resolveOpenApi(context);
        Paths paths = openAPI.getPaths();
        if (paths == null) {
            paths = new Paths();
            openAPI.setPaths(paths);
        }

        var resultPathItemsMap = new HashMap<String, List<PathItem>>();

        for (UriMatchTemplate matchTemplate : matchTemplates) {

            var result = new StringBuilder();

            boolean varProcess = false;
            boolean valueProcess = false;
            boolean isFirstVarChar = true;
            boolean needToSkip = false;
            final String pathString = matchTemplate.toPathString();
            for (char c : pathString.toCharArray()) {
                if (varProcess) {
                    if (isFirstVarChar) {
                        isFirstVarChar = false;
                        if (c == '?' || c == '.') {
                            needToSkip = true;
                            result.deleteCharAt(result.length() - 1);
                            continue;
                        } else if (c == '+' || c == '0') {
                            continue;
                        } else if (c == '/') {
                            needToSkip = true;
                            result.deleteCharAt(result.length() - 1);
                            continue;
                        }
                    }
                    if (c == ':') {
                        valueProcess = true;
                        continue;
                    }
                    if (c == '}') {
                        varProcess = false;
                        valueProcess = false;
                        if (!needToSkip) {
                            result.append('}');
                        }
                        needToSkip = false;
                        continue;
                    }
                    if (valueProcess || needToSkip) {
                        continue;
                    }
                }
                if (c == '{') {
                    varProcess = true;
                    isFirstVarChar = true;
                }
                result.append(c);
            }

            String resultPath = replacePlaceholders(result.toString(), context);

            if (!resultPath.startsWith(StringUtil.SLASH) && !resultPath.startsWith(DOLLAR)) {
                resultPath = StringUtil.SLASH + resultPath;
            }
            String contextPath = ConfigUtils.getConfigProperty(MICRONAUT_SERVER_CONTEXT_PATH, context);
            if (StringUtils.isNotEmpty(contextPath)) {
                if (!contextPath.startsWith(StringUtil.SLASH) && !contextPath.startsWith(DOLLAR)) {
                    contextPath = StringUtil.SLASH + contextPath;
                }
                if (contextPath.endsWith(StringUtil.SLASH)) {
                    contextPath = contextPath.substring(0, contextPath.length() - 1);
                }
                resultPath = contextPath + resultPath;
            }

            var finalPaths = new HashMap<Integer, String>();
            finalPaths.put(-1, resultPath);
            if (CollectionUtils.isNotEmpty(matchTemplate.getVariables())) {
                var optionalVars = new ArrayList<String>();
                // need check not required path variables
                for (UriMatchVariable var : matchTemplate.getVariables()) {
                    if (var.isQuery() || !var.isOptional() || var.isExploded()) {
                        continue;
                    }
                    optionalVars.add(var.getName());
                }
                if (CollectionUtils.isNotEmpty(optionalVars)) {

                    int i = 0;
                    for (String var : optionalVars) {
                        if (finalPaths.isEmpty()) {
                            finalPaths.put(i, resultPath + SLASH + OPEN_BRACE + var + CLOSE_BRACE);
                            i++;
                            continue;
                        }
                        for (Map.Entry<Integer, String> entry : finalPaths.entrySet()) {
                            if (entry.getKey() + 1 < i) {
                                continue;
                            }
                            finalPaths.put(i, entry.getValue() + SLASH + OPEN_BRACE + var + CLOSE_BRACE);
                        }
                        i++;
                    }
                }
            }

            for (String finalPath : finalPaths.values()) {
                List<PathItem> resultPathItems = resultPathItemsMap.computeIfAbsent(finalPath, k -> new ArrayList<>());
                resultPathItems.add(paths.computeIfAbsent(finalPath, key -> new PathItem()));
            }
        }

        return resultPathItemsMap;
    }

    /**
     * Processes {@link SecurityScheme}
     * annotations.
     *
     * @param element The element
     * @param context The visitor context
     */
    protected void processSecuritySchemes(ClassElement element, VisitorContext context) {
        var values = element.getAnnotationValuesByType(SecurityScheme.class);
        final OpenAPI openApi = Utils.resolveOpenApi(context);
        ConvertUtils.addSecuritySchemes(openApi, values, context);
    }

    /**
     * Converts annotation to model.
     *
     * @param <T> The model type.
     * @param <A> The annotation type.
     * @param element The element to process.
     * @param context The context.
     * @param annotationType The annotation type.
     * @param modelType The model type.
     * @param tagList The initial list of models.
     *
     * @return A list of model objects.
     */
    protected <T, A extends Annotation> List<T> processOpenApiAnnotation(Element element, VisitorContext context, Class<A> annotationType, Class<T> modelType, List<T> tagList) {
        List<AnnotationValue<A>> annotations = element.getAnnotationValuesByType(annotationType);
        if (CollectionUtils.isNotEmpty(annotations)) {
            if (CollectionUtils.isEmpty(tagList)) {
                tagList = new ArrayList<>();
            }
            for (AnnotationValue<A> tag : annotations) {
                Map<CharSequence, Object> values;
                var tagValues = tag.getValues();
                if (tag.getAnnotationName().equals(io.swagger.v3.oas.annotations.security.SecurityRequirement.class.getName())
                    && !tagValues.isEmpty()) {
                    Object name = tagValues.get(PROP_NAME);
                    Object scopes = tagValues.get(PROP_SCOPES);
                    if (scopes == null) {
                        scopes = new ArrayList<String>();
                    }
                    values = Collections.singletonMap((CharSequence) name, scopes);
                } else {
                    values = tagValues;
                }
                Optional<T> tagOpt = toValue(values, context, modelType, null);
                if (tagOpt.isPresent()) {
                    T tagObj = tagOpt.get();
                    // skip all existed tags
                    boolean alreadyExists = false;
                    if (CollectionUtils.isNotEmpty(tagList) && tag.getAnnotationName().equals(io.swagger.v3.oas.annotations.tags.Tag.class.getName())) {
                        for (T existedTag : tagList) {
                            if (((Tag) existedTag).getName().equals(((Tag) tagObj).getName())) {
                                alreadyExists = true;
                                break;
                            }
                        }
                    }
                    if (!alreadyExists) {
                        tagList.add(tagObj);
                    }
                }
            }
        }
        return tagList;
    }
}
