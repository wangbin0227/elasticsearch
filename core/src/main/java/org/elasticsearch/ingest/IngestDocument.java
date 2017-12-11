/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.ingest;

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.index.mapper.IdFieldMapper;
import org.elasticsearch.index.mapper.IndexFieldMapper;
import org.elasticsearch.index.mapper.ParentFieldMapper;
import org.elasticsearch.index.mapper.RoutingFieldMapper;
import org.elasticsearch.index.mapper.SourceFieldMapper;
import org.elasticsearch.index.mapper.TypeFieldMapper;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.script.TemplateScript;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a single document being captured before indexing and holds the source and metadata (like id, type and index).
 */
public final class IngestDocument {

    public static final String INGEST_KEY = "_ingest";
    private static final String INGEST_KEY_PREFIX = INGEST_KEY + ".";
    private static final String SOURCE_PREFIX = SourceFieldMapper.NAME + ".";

    static final String TIMESTAMP = "timestamp";

    private final Map<String, Object> sourceAndMetadata;
    private final Map<String, Object> ingestMetadata;

    public IngestDocument(String index, String type, String id, String routing, String parent, Map<String, Object> source) {
        this.sourceAndMetadata = new HashMap<>();
        this.sourceAndMetadata.putAll(source);
        this.sourceAndMetadata.put(MetaData.INDEX.getFieldName(), index);
        this.sourceAndMetadata.put(MetaData.TYPE.getFieldName(), type);
        this.sourceAndMetadata.put(MetaData.ID.getFieldName(), id);
        if (routing != null) {
            this.sourceAndMetadata.put(MetaData.ROUTING.getFieldName(), routing);
        }
        if (parent != null) {
            this.sourceAndMetadata.put(MetaData.PARENT.getFieldName(), parent);
        }

        this.ingestMetadata = new HashMap<>();
        this.ingestMetadata.put(TIMESTAMP, ZonedDateTime.now(ZoneOffset.UTC));
    }

    /**
     * Copy constructor that creates a new {@link IngestDocument} which has exactly the same properties as the one provided as argument
     */
    public IngestDocument(IngestDocument other) {
        this(deepCopyMap(other.sourceAndMetadata), deepCopyMap(other.ingestMetadata));
    }

    /**
     * Constructor needed for testing that allows to create a new {@link IngestDocument} given the provided elasticsearch metadata,
     * source and ingest metadata. This is needed because the ingest metadata will be initialized with the current timestamp at
     * init time, which makes equality comparisons impossible in tests.
     */
    public IngestDocument(Map<String, Object> sourceAndMetadata, Map<String, Object> ingestMetadata) {
        this.sourceAndMetadata = sourceAndMetadata;
        this.ingestMetadata = ingestMetadata;
    }

    /**
     * Returns the value contained in the document for the provided path
     * @param path The path within the document in dot-notation
     * @param clazz The expected class of the field value
     * @return the value for the provided path if existing, null otherwise
     * @throws IllegalArgumentException if the path is null, empty, invalid, if the field doesn't exist
     * or if the field that is found at the provided path is not of the expected type.
     */
    public <T> T getFieldValue(String path, Class<T> clazz) {
        FieldPath fieldPath = new FieldPath(path);
        Object context = fieldPath.initialContext;
        for (String pathElement : fieldPath.pathElements) {
            context = resolve(pathElement, path, context);
        }
        return cast(path, context, clazz);
    }

    /**
     * Returns the value contained in the document for the provided path
     *
     * @param path The path within the document in dot-notation
     * @param clazz The expected class of the field value
     * @param ignoreMissing The flag to determine whether to throw an exception when `path` is not found in the document.
     * @return the value for the provided path if existing, null otherwise.
     * @throws IllegalArgumentException only if ignoreMissing is false and the path is null, empty, invalid, if the field doesn't exist
     * or if the field that is found at the provided path is not of the expected type.
     */
    public <T> T getFieldValue(String path, Class<T> clazz, boolean ignoreMissing) {
        try {
            return getFieldValue(path, clazz);
        } catch (IllegalArgumentException e) {
            if (ignoreMissing && hasField(path) != true) {
                return null;
            } else {
                throw e;
            }
        }
    }

    /**
     * Returns the value contained in the document with the provided templated path
     * @param pathTemplate The path within the document in dot-notation
     * @param clazz The expected class fo the field value
     * @return the value fro the provided path if existing, null otherwise
     * @throws IllegalArgumentException if the pathTemplate is null, empty, invalid, if the field doesn't exist,
     * or if the field that is found at the provided path is not of the expected type.
     */
    public <T> T getFieldValue(TemplateScript.Factory pathTemplate, Class<T> clazz) {
        return getFieldValue(renderTemplate(pathTemplate), clazz);
    }

    /**
     * Returns the value contained in the document for the provided path as a byte array.
     * If the path value is a string, a base64 decode operation will happen.
     * If the path value is a byte array, it is just returned
     * @param path The path within the document in dot-notation
     * @return the byte array for the provided path if existing
     * @throws IllegalArgumentException if the path is null, empty, invalid, if the field doesn't exist
     * or if the field that is found at the provided path is not of the expected type.
     */
    public byte[] getFieldValueAsBytes(String path) {
        return getFieldValueAsBytes(path, false);
    }

    /**
     * Returns the value contained in the document for the provided path as a byte array.
     * If the path value is a string, a base64 decode operation will happen.
     * If the path value is a byte array, it is just returned
     * @param path The path within the document in dot-notation
     * @param ignoreMissing The flag to determine whether to throw an exception when `path` is not found in the document.
     * @return the byte array for the provided path if existing
     * @throws IllegalArgumentException if the path is null, empty, invalid, if the field doesn't exist
     * or if the field that is found at the provided path is not of the expected type.
     */
    public byte[] getFieldValueAsBytes(String path, boolean ignoreMissing) {
        Object object = getFieldValue(path, Object.class, ignoreMissing);
        if (object == null) {
            return null;
        } else if (object instanceof byte[]) {
            return (byte[]) object;
        } else if (object instanceof String) {
            return Base64.getDecoder().decode(object.toString());
        } else {
            throw new IllegalArgumentException("Content field [" + path + "] of unknown type [" + object.getClass().getName() +
                "], must be string or byte array");
        }
    }

    /**
     * Checks whether the document contains a value for the provided templated path
     * @param fieldPathTemplate the template for the path within the document in dot-notation
     * @return true if the document contains a value for the field, false otherwise
     * @throws IllegalArgumentException if the path is null, empty or invalid
     */
    public boolean hasField(TemplateScript.Factory fieldPathTemplate) {
        return hasField(renderTemplate(fieldPathTemplate));
    }

    /**
     * Checks whether the document contains a value for the provided path
     * @param path The path within the document in dot-notation
     * @return true if the document contains a value for the field, false otherwise
     * @throws IllegalArgumentException if the path is null, empty or invalid.
     */
    public boolean hasField(String path) {
        return hasField(path, false);
    }

    /**
     * Checks whether the document contains a value for the provided path
     * @param path The path within the document in dot-notation
     * @param failOutOfRange Whether to throw an IllegalArgumentException if array is accessed outside of its range
     * @return true if the document contains a value for the field, false otherwise
     * @throws IllegalArgumentException if the path is null, empty or invalid.
     */
    public boolean hasField(String path, boolean failOutOfRange) {
        FieldPath fieldPath = new FieldPath(path);
        Object context = fieldPath.initialContext;
        for (int i = 0; i < fieldPath.pathElements.length - 1; i++) {
            String pathElement = fieldPath.pathElements[i];
            if (context == null) {
                return false;
            }
            if (context instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) context;
                context = map.get(pathElement);
            } else if (context instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) context;
                try {
                    int index = Integer.parseInt(pathElement);
                    if (index < 0 || index >= list.size()) {
                        if (failOutOfRange) {
                            throw new IllegalArgumentException("[" + index + "] is out of bounds for array with length [" +
                                    list.size() + "] as part of path [" + path +"]");
                        } else {
                            return false;
                        }
                    }
                    context = list.get(index);
                } catch (NumberFormatException e) {
                    return false;
                }

            } else {
                return false;
            }
        }

        String leafKey = fieldPath.pathElements[fieldPath.pathElements.length - 1];
        if (context instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) context;
            return map.containsKey(leafKey);
        }
        if (context instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) context;
            try {
                int index = Integer.parseInt(leafKey);
                if (index >= 0 && index < list.size()) {
                    return true;
                } else {
                    if (failOutOfRange) {
                        throw new IllegalArgumentException("[" + index + "] is out of bounds for array with length [" +
                                list.size() + "] as part of path [" + path +"]");
                    } else {
                        return false;
                    }
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * Removes the field identified by the provided path.
     * @param fieldPathTemplate Resolves to the path with dot-notation within the document
     * @throws IllegalArgumentException if the path is null, empty, invalid or if the field doesn't exist.
     */
    public void removeField(TemplateScript.Factory fieldPathTemplate) {
        removeField(renderTemplate(fieldPathTemplate));
    }

    /**
     * Removes the field identified by the provided path.
     * @param path the path of the field to be removed
     * @throws IllegalArgumentException if the path is null, empty, invalid or if the field doesn't exist.
     */
    public void removeField(String path) {
        FieldPath fieldPath = new FieldPath(path);
        Object context = fieldPath.initialContext;
        for (int i = 0; i < fieldPath.pathElements.length - 1; i++) {
            context = resolve(fieldPath.pathElements[i], path, context);
        }

        String leafKey = fieldPath.pathElements[fieldPath.pathElements.length - 1];
        if (context instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) context;
            if (map.containsKey(leafKey)) {
                map.remove(leafKey);
                return;
            }
            throw new IllegalArgumentException("field [" + leafKey + "] not present as part of path [" + path + "]");
        }
        if (context instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) context;
            int index;
            try {
                index = Integer.parseInt(leafKey);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("[" + leafKey + "] is not an integer, cannot be used as an index as part of path [" +
                        path + "]", e);
            }
            if (index < 0 || index >= list.size()) {
                throw new IllegalArgumentException("[" + index + "] is out of bounds for array with length [" + list.size() +
                        "] as part of path [" + path + "]");
            }
            list.remove(index);
            return;
        }

        if (context == null) {
            throw new IllegalArgumentException("cannot remove [" + leafKey + "] from null as part of path [" + path + "]");
        }
        throw new IllegalArgumentException("cannot remove [" + leafKey + "] from object of type [" + context.getClass().getName() +
                "] as part of path [" + path + "]");
    }

    private static Object resolve(String pathElement, String fullPath, Object context) {
        if (context == null) {
            throw new IllegalArgumentException("cannot resolve [" + pathElement + "] from null as part of path [" + fullPath + "]");
        }
        if (context instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) context;
            if (map.containsKey(pathElement)) {
                return map.get(pathElement);
            }
            throw new IllegalArgumentException("field [" + pathElement + "] not present as part of path [" + fullPath + "]");
        }
        if (context instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) context;
            int index;
            try {
                index = Integer.parseInt(pathElement);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("[" + pathElement + "] is not an integer, cannot be used as an index as part of path ["
                        + fullPath + "]", e);
            }
            if (index < 0 || index >= list.size()) {
                throw new IllegalArgumentException("[" + index + "] is out of bounds for array with length [" + list.size() +
                        "] as part of path [" + fullPath + "]");
            }
            return list.get(index);
        }
        throw new IllegalArgumentException("cannot resolve [" + pathElement + "] from object of type [" + context.getClass().getName() +
                "] as part of path [" + fullPath + "]");
    }

    /**
     * Appends the provided value to the provided path in the document.
     * Any non existing path element will be created.
     * If the path identifies a list, the value will be appended to the existing list.
     * If the path identifies a scalar, the scalar will be converted to a list and
     * the provided value will be added to the newly created list.
     * Supports multiple values too provided in forms of list, in that case all the values will be appended to the
     * existing (or newly created) list.
     * @param path The path within the document in dot-notation
     * @param value The value or values to append to the existing ones
     * @throws IllegalArgumentException if the path is null, empty or invalid.
     */
    public void appendFieldValue(String path, Object value) {
        setFieldValue(path, value, true);
    }

    /**
     * Appends the provided value to the provided path in the document.
     * Any non existing path element will be created.
     * If the path identifies a list, the value will be appended to the existing list.
     * If the path identifies a scalar, the scalar will be converted to a list and
     * the provided value will be added to the newly created list.
     * Supports multiple values too provided in forms of list, in that case all the values will be appended to the
     * existing (or newly created) list.
     * @param fieldPathTemplate Resolves to the path with dot-notation within the document
     * @param valueSource The value source that will produce the value or values to append to the existing ones
     * @throws IllegalArgumentException if the path is null, empty or invalid.
     */
    public void appendFieldValue(TemplateScript.Factory fieldPathTemplate, ValueSource valueSource) {
        Map<String, Object> model = createTemplateModel();
        appendFieldValue(fieldPathTemplate.newInstance(model).execute(), valueSource.copyAndResolve(model));
    }

    /**
     * Sets the provided value to the provided path in the document.
     * Any non existing path element will be created.
     * If the last item in the path is a list, the value will replace the existing list as a whole.
     * Use {@link #appendFieldValue(String, Object)} to append values to lists instead.
     * @param path The path within the document in dot-notation
     * @param value The value to put in for the path key
     * @throws IllegalArgumentException if the path is null, empty, invalid or if the value cannot be set to the
     * item identified by the provided path.
     */
    public void setFieldValue(String path, Object value) {
        setFieldValue(path, value, false);
    }

    /**
     * Sets the provided value to the provided path in the document.
     * Any non existing path element will be created. If the last element is a list,
     * the value will replace the existing list.
     * @param fieldPathTemplate Resolves to the path with dot-notation within the document
     * @param valueSource The value source that will produce the value to put in for the path key
     * @throws IllegalArgumentException if the path is null, empty, invalid or if the value cannot be set to the
     * item identified by the provided path.
     */
    public void setFieldValue(TemplateScript.Factory fieldPathTemplate, ValueSource valueSource) {
        Map<String, Object> model = createTemplateModel();
        setFieldValue(fieldPathTemplate.newInstance(model).execute(), valueSource.copyAndResolve(model), false);
    }

    private void setFieldValue(String path, Object value, boolean append) {
        FieldPath fieldPath = new FieldPath(path);
        Object context = fieldPath.initialContext;
        for (int i = 0; i < fieldPath.pathElements.length - 1; i++) {
            String pathElement = fieldPath.pathElements[i];
            if (context == null) {
                throw new IllegalArgumentException("cannot resolve [" + pathElement + "] from null as part of path [" + path + "]");
            }
            if (context instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) context;
                if (map.containsKey(pathElement)) {
                    context = map.get(pathElement);
                } else {
                    HashMap<Object, Object> newMap = new HashMap<>();
                    map.put(pathElement, newMap);
                    context = newMap;
                }
            } else if (context instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) context;
                int index;
                try {
                    index = Integer.parseInt(pathElement);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("[" + pathElement +
                            "] is not an integer, cannot be used as an index as part of path [" + path + "]", e);
                }
                if (index < 0 || index >= list.size()) {
                    throw new IllegalArgumentException("[" + index + "] is out of bounds for array with length [" +
                            list.size() + "] as part of path [" + path + "]");
                }
                context = list.get(index);
            } else {
                throw new IllegalArgumentException("cannot resolve [" + pathElement + "] from object of type [" +
                        context.getClass().getName() + "] as part of path [" + path + "]");
            }
        }

        String leafKey = fieldPath.pathElements[fieldPath.pathElements.length - 1];
        if (context == null) {
            throw new IllegalArgumentException("cannot set [" + leafKey + "] with null parent as part of path [" + path + "]");
        }
        if (context instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) context;
            if (append) {
                if (map.containsKey(leafKey)) {
                    Object object = map.get(leafKey);
                    List<Object> list = appendValues(object, value);
                    if (list != object) {
                        map.put(leafKey, list);
                    }
                } else {
                    List<Object> list = new ArrayList<>();
                    appendValues(list, value);
                    map.put(leafKey, list);
                }
                return;
            }
            map.put(leafKey, value);
        } else if (context instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) context;
            int index;
            try {
                index = Integer.parseInt(leafKey);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("[" + leafKey + "] is not an integer, cannot be used as an index as part of path [" +
                        path + "]", e);
            }
            if (index < 0 || index >= list.size()) {
                throw new IllegalArgumentException("[" + index + "] is out of bounds for array with length [" + list.size() +
                        "] as part of path [" + path + "]");
            }
            if (append) {
                Object object = list.get(index);
                List<Object> newList = appendValues(object, value);
                if (newList != object) {
                    list.set(index, newList);
                }
                return;
            }
            list.set(index, value);
        } else {
            throw new IllegalArgumentException("cannot set [" + leafKey + "] with parent object of type [" +
                    context.getClass().getName() + "] as part of path [" + path + "]");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> appendValues(Object maybeList, Object value) {
        List<Object> list;
        if (maybeList instanceof List) {
            //maybeList is already a list, we append the provided values to it
            list = (List<Object>) maybeList;
        } else {
            //maybeList is a scalar, we convert it to a list and append the provided values to it
            list = new ArrayList<>();
            list.add(maybeList);
        }
        appendValues(list, value);
        return list;
    }

    private static void appendValues(List<Object> list, Object value) {
        if (value instanceof List) {
            List<?> valueList = (List<?>) value;
            valueList.stream().forEach(list::add);
        } else {
            list.add(value);
        }
    }

    private static <T> T cast(String path, Object object, Class<T> clazz) {
        if (object == null) {
            return null;
        }
        if (clazz.isInstance(object)) {
            return clazz.cast(object);
        }
        throw new IllegalArgumentException("field [" + path + "] of type [" + object.getClass().getName() + "] cannot be cast to [" +
                clazz.getName() + "]");
    }

    public String renderTemplate(TemplateScript.Factory template) {
        return template.newInstance(createTemplateModel()).execute();
    }

    private Map<String, Object> createTemplateModel() {
        Map<String, Object> model = new HashMap<>(sourceAndMetadata);
        model.put(SourceFieldMapper.NAME, sourceAndMetadata);
        // If there is a field in the source with the name '_ingest' it gets overwritten here,
        // if access to that field is required then it get accessed via '_source._ingest'
        model.put(INGEST_KEY, ingestMetadata);
        return model;
    }

    /**
     * one time operation that extracts the metadata fields from the ingest document and returns them.
     * Metadata fields that used to be accessible as ordinary top level fields will be removed as part of this call.
     */
    public Map<MetaData, String> extractMetadata() {
        Map<MetaData, String> metadataMap = new EnumMap<>(MetaData.class);
        for (MetaData metaData : MetaData.values()) {
            metadataMap.put(metaData, cast(metaData.getFieldName(), sourceAndMetadata.remove(metaData.getFieldName()), String.class));
        }
        return metadataMap;
    }

    /**
     * Returns the available ingest metadata fields, by default only timestamp, but it is possible to set additional ones.
     * Use only for reading values, modify them instead using {@link #setFieldValue(String, Object)} and {@link #removeField(String)}
     */
    public Map<String, Object> getIngestMetadata() {
        return this.ingestMetadata;
    }

    /**
     * Returns the document including its metadata fields, unless {@link #extractMetadata()} has been called, in which case the
     * metadata fields will not be present anymore.
     * Modify the document instead using {@link #setFieldValue(String, Object)} and {@link #removeField(String)}
     */
    public Map<String, Object> getSourceAndMetadata() {
        return this.sourceAndMetadata;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> deepCopyMap(Map<K, V> source) {
        return (Map<K, V>) deepCopy(source);
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map) {
            Map<?, ?> mapValue = (Map<?, ?>) value;
            Map<Object, Object> copy = new HashMap<>(mapValue.size());
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                copy.put(entry.getKey(), deepCopy(entry.getValue()));
            }
            return copy;
        } else if (value instanceof List) {
            List<?> listValue = (List<?>) value;
            List<Object> copy = new ArrayList<>(listValue.size());
            for (Object itemValue : listValue) {
                copy.add(deepCopy(itemValue));
            }
            return copy;
        } else if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            return Arrays.copyOf(bytes, bytes.length);
        } else if (value == null || value instanceof String || value instanceof Integer ||
            value instanceof Long || value instanceof Float ||
            value instanceof Double || value instanceof Boolean) {
            return value;
        } else if (value instanceof Date) {
            return ((Date) value).clone();
        } else if (value instanceof ZonedDateTime) {
            ZonedDateTime zonedDateTime = (ZonedDateTime) value;
            return ZonedDateTime.of(zonedDateTime.toLocalDate(), zonedDateTime.toLocalTime(), zonedDateTime.getZone());
        } else {
            throw new IllegalArgumentException("unexpected value type [" + value.getClass() + "]");
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) { return true; }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        IngestDocument other = (IngestDocument) obj;
        return Objects.equals(sourceAndMetadata, other.sourceAndMetadata) &&
                Objects.equals(ingestMetadata, other.ingestMetadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceAndMetadata, ingestMetadata);
    }

    @Override
    public String toString() {
        return "IngestDocument{" +
                " sourceAndMetadata=" + sourceAndMetadata +
                ", ingestMetadata=" + ingestMetadata +
                '}';
    }

    public enum MetaData {
        INDEX(IndexFieldMapper.NAME),
        TYPE(TypeFieldMapper.NAME),
        ID(IdFieldMapper.NAME),
        ROUTING(RoutingFieldMapper.NAME),
        PARENT(ParentFieldMapper.NAME);

        private final String fieldName;

        MetaData(String fieldName) {
            this.fieldName = fieldName;
        }

        public String getFieldName() {
            return fieldName;
        }
    }

    private class FieldPath {

        private final String[] pathElements;
        private final Object initialContext;

        private FieldPath(String path) {
            if (Strings.isEmpty(path)) {
                throw new IllegalArgumentException("path cannot be null nor empty");
            }
            String newPath;
            if (path.startsWith(INGEST_KEY_PREFIX)) {
                initialContext = ingestMetadata;
                newPath = path.substring(INGEST_KEY_PREFIX.length(), path.length());
            } else {
                initialContext = sourceAndMetadata;
                if (path.startsWith(SOURCE_PREFIX)) {
                    newPath = path.substring(SOURCE_PREFIX.length(), path.length());
                } else {
                    newPath = path;
                }
            }
            this.pathElements = newPath.split("\\.");
            if (pathElements.length == 1 && pathElements[0].isEmpty()) {
                throw new IllegalArgumentException("path [" + path + "] is not valid");
            }
        }

    }
}
