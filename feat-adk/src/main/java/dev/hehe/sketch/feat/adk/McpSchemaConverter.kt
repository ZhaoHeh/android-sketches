package dev.hehe.sketch.feat.adk

import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type

internal object McpSchemaConverter {
    private val supportedKeys = setOf(
        "type",
        "properties",
        "items",
        "required",
        "description",
        "enum"
    )

    fun toAdkSchema(schema: Map<String, Any?>): Result<Schema> = runCatching {
        convert(schema, path = "$")
    }

    private fun convert(schema: Map<String, Any?>, path: String): Schema {
        val unsupported = schema.keys - supportedKeys
        require(unsupported.isEmpty()) {
            "$path contains unsupported JSON Schema keywords: ${unsupported.sorted().joinToString()}"
        }

        val typeName = schema["type"] as? String
            ?: throw IllegalArgumentException("$path.type must be a string")
        val type = when (typeName.lowercase()) {
            "object" -> Type.OBJECT
            "string" -> Type.STRING
            "integer" -> Type.INTEGER
            "number" -> Type.NUMBER
            "boolean" -> Type.BOOLEAN
            "array" -> Type.ARRAY
            else -> throw IllegalArgumentException("$path.type '$typeName' is unsupported")
        }

        val properties = when (val raw = schema["properties"]) {
            null -> null
            is Map<*, *> -> raw.entries.associate { (key, value) ->
                require(key is String) { "$path.properties keys must be strings" }
                require(value is Map<*, *>) { "$path.properties.$key must be an object" }
                key to convert(value.toStringKeyMap("$path.properties.$key"), "$path.properties.$key")
            }
            else -> throw IllegalArgumentException("$path.properties must be an object")
        }
        if (type != Type.OBJECT) {
            require(properties == null) { "$path.properties is only valid for object schemas" }
        }

        val items = when (val raw = schema["items"]) {
            null -> null
            is Map<*, *> -> convert(raw.toStringKeyMap("$path.items"), "$path.items")
            else -> throw IllegalArgumentException("$path.items must be an object")
        }
        if (type == Type.ARRAY) {
            require(items != null) { "$path.items is required for array schemas" }
        } else {
            require(items == null) { "$path.items is only valid for array schemas" }
        }

        val required = when (val raw = schema["required"]) {
            null -> null
            is List<*> -> raw.mapIndexed { index, value ->
                value as? String
                    ?: throw IllegalArgumentException("$path.required[$index] must be a string")
            }
            else -> throw IllegalArgumentException("$path.required must be an array")
        }
        if (type != Type.OBJECT) {
            require(required == null) { "$path.required is only valid for object schemas" }
        }
        required?.forEach { name ->
            require(properties?.containsKey(name) == true) {
                "$path.required contains unknown property '$name'"
            }
        }

        val description = schema["description"]?.let {
            it as? String ?: throw IllegalArgumentException("$path.description must be a string")
        }
        val enum = when (val raw = schema["enum"]) {
            null -> null
            is List<*> -> raw.mapIndexed { index, value ->
                value as? String
                    ?: throw IllegalArgumentException("$path.enum[$index] must be a string")
            }
            else -> throw IllegalArgumentException("$path.enum must be an array")
        }

        return Schema(
            type = type,
            properties = properties,
            items = items,
            required = required,
            description = description,
            enum = enum
        )
    }

    private fun Map<*, *>.toStringKeyMap(path: String): Map<String, Any?> =
        entries.associate { (key, value) ->
            require(key is String) { "$path keys must be strings" }
            key to value
        }
}

internal object McpSchemas {
    fun string(description: String, enum: List<String>? = null): Map<String, Any?> = buildMap {
        put("type", "string")
        put("description", description)
        enum?.let { put("enum", it) }
    }

    fun integer(description: String): Map<String, Any?> = mapOf(
        "type" to "integer",
        "description" to description
    )

    fun objectSchema(
        properties: Map<String, Map<String, Any?>>,
        required: List<String> = emptyList(),
        description: String? = null
    ): Map<String, Any?> = buildMap {
        put("type", "object")
        put("properties", properties)
        if (required.isNotEmpty()) put("required", required)
        description?.let { put("description", it) }
    }
}
