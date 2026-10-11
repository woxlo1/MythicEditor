package dev.mythiceditor.core;

import java.util.regex.Pattern;

public final class Kinds {
    public static final String SKILL = "skill";
    public static final String MOB = "mob";
    public static final String ITEM = "item";
    public static final String DROPTABLE = "droptable";

    /** MythicMobs internal names: letters, digits, underscore, dash, dot. No spaces, no YAML-significant characters. */
    public static final Pattern ID = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.\\-]{0,63}$");

    private Kinds() {}

    public static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }
}
