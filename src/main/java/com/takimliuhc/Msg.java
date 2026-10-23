package com.takimliuhc;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class Msg {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Msg() {}

    /** & renk kodlu metni Component'e çevirir. */
    public static Component c(String s) {
        return LEGACY.deserialize(s == null ? "" : s);
    }

    /** Eşya isimleri için italik kapalı. */
    public static Component item(String s) {
        return c(s).decoration(TextDecoration.ITALIC, false);
    }
}
