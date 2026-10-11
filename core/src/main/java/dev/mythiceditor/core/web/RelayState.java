package dev.mythiceditor.core.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import dev.mythiceditor.core.Json;

/** What the automatic Worker deploy remembers (plugins/MythicEditor/relay.json). Contains the relay secret. */
public record RelayState(String url, String secret, String workerHash, String workerName) {

    /** @return the saved state, or null when there is none / it cannot be read */
    public static RelayState load(Path file) {
        try {
            if (file == null || !Files.isRegularFile(file)) return null;
            JsonObject o = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            String url = nz(Json.str(o, "url"));
            String secret = nz(Json.str(o, "secret"));
            if (url.isEmpty() || secret.isEmpty()) return null;
            return new RelayState(url, secret, nz(Json.str(o, "workerHash")), nz(Json.str(o, "workerName")));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public void save(Path file) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("url", url);
        o.addProperty("secret", secret);
        o.addProperty("workerHash", workerHash);
        o.addProperty("workerName", workerName);
        Files.writeString(file, new Gson().newBuilder().setPrettyPrinting().create().toJson(o) + "\n", StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // not a POSIX file system (Windows)
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
