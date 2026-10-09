package com.settlementengine.core.lab;

import com.settlementengine.core.events.KafkaTopics;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** The engine's topics, read from {@link KafkaTopics} itself so the list is never hand-copied. */
public final class LabTopics {

    private static final List<String> ALL = load();

    private LabTopics() {
    }

    public static List<String> all() {
        return ALL;
    }

    public static boolean isKnown(String topic) {
        return ALL.contains(topic);
    }

    private static List<String> load() {
        List<String> topics = new ArrayList<>();
        for (Field f : KafkaTopics.class.getDeclaredFields()) {
            if (Modifier.isPublic(f.getModifiers()) && Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                try {
                    topics.add((String) f.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return List.copyOf(topics);
    }
}
