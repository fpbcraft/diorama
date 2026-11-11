package dev.fpbcraft.diorama;

import dev.diorama.waypoint.Waypoint;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/** Small reflective bridge so no optional integration becomes a hard mod dependency. */
final class ReflectSupport {
    private ReflectSupport() {}

    static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && matches(method.getParameterTypes(), args)) {
                return method.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name + "/" + args.length);
    }

    static Object staticCall(String className, String name, Object... args) throws ReflectiveOperationException {
        Class<?> type = Class.forName(className);
        for (Method method : type.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getName().equals(name)
                    && matches(method.getParameterTypes(), args)) {
                return method.invoke(null, args);
            }
        }
        throw new NoSuchMethodException(className + "." + name);
    }

    private static boolean matches(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < types.length; i++) {
            if (args[i] == null) {
                if (types[i].isPrimitive()) return false;
            } else if (!wrap(types[i]).isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == boolean.class) return Boolean.class;
        return type;
    }

    static String text(Object object) throws ReflectiveOperationException {
        if (object == null) return "";
        if (object instanceof String s) return s;
        return String.valueOf(call(object, "getString"));
    }

    static Waypoint marker(String id, String name, BlockPos pos, String dimension, int color, String initials) {
        Waypoint waypoint = new Waypoint(name, pos.getX(), pos.getY(), pos.getZ(), dimension, color);
        waypoint.id = UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));
        waypoint.kind = Waypoint.Kind.LIVE;
        waypoint.initials = initials;
        return waypoint;
    }
}
