package dev.mythiceditor.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/** Thin JSON facade. Gson HTML-escaping stays ON: it turns angle brackets and ampersands into escaped sequences (XSS defence in depth). */
public final class Json {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private Json() {}

    public static String toJson(Object o) {
        return GSON.toJson(o);
    }

    public static JsonObject parseObject(String s) throws JsonParseException {
        JsonElement e = JsonParser.parseString(s);
        if (!e.isJsonObject()) throw new JsonParseException("JSON object expected");
        return e.getAsJsonObject();
    }

    public static JsonElement parse(String s) {
        return JsonParser.parseString(s);
    }

    public static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    public static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }
}
