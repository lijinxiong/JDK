/*
 * Copyright (c) 2011, 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */
package jdk.vm.ci.hotspot;

import static jdk.internal.misc.Unsafe.ADDRESS_SIZE;
import static jdk.vm.ci.hotspot.CompilerToVM.compilerToVM;
import static jdk.vm.ci.hotspot.HotSpotModifiers.jvmFieldModifiers;
import static jdk.vm.ci.hotspot.HotSpotVMConfig.config;
import static jdk.vm.ci.hotspot.UnsafeAccess.UNSAFE;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.HashMap;

import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.UnresolvedJavaType;

/**
 * Represents a field in a HotSpot type.
 */
class HotSpotResolvedJavaFieldImpl implements HotSpotResolvedJavaField {

    private final HotSpotResolvedObjectTypeImpl holder;
    private JavaType type;
    private final int offset;
    private final short index;

    /**
     * This value contains all flags as stored in the VM including internal ones.
     */
    private final int modifiers;

    HotSpotResolvedJavaFieldImpl(HotSpotResolvedObjectTypeImpl holder, JavaType type, long offset, int modifiers, int index) {
        this.holder = holder;
        this.type = type;
        this.index = (short) index;
        assert this.index == index;
        assert offset != -1;
        assert offset == (int) offset : "offset larger than int";
        this.offset = (int) offset;
        this.modifiers = modifiers;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof HotSpotResolvedJavaField) {
            HotSpotResolvedJavaFieldImpl that = (HotSpotResolvedJavaFieldImpl) obj;
            if (that.offset != this.offset || that.isStatic() != this.isStatic()) {
                return false;
            } else if (this.holder.equals(that.holder)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int hashCode() {
        return holder.hashCode() ^ offset;
    }

    @Override
    public int getModifiers() {
        return modifiers & jvmFieldModifiers();
    }

    @Override
    public boolean isInternal() {
        return (modifiers & config().jvmAccFieldInternal) != 0;
    }

    /**
     * Determines if a given object contains this field.
     *
     * @return true iff this is a non-static field and its declaring class is assignable from
     *         {@code object}'s class
     */
    @Override
    public boolean isInObject(JavaConstant constant) {
        if (isStatic()) {
            return false;
        }
        Object object = ((HotSpotObjectConstantImpl) constant).object();
        return getDeclaringClass().isAssignableFrom(HotSpotResolvedObjectTypeImpl.fromObjectClass(object.getClass()));
    }

    @Override
    public HotSpotResolvedObjectTypeImpl getDeclaringClass() {
        return holder;
    }

    @Override
    public String getName() {
        return holder.createFieldInfo(index).getName();
    }

    @Override
    public JavaType getType() {
        // Pull field into local variable to prevent a race causing
        // a ClassCastException below
        JavaType currentType = type;
        if (currentType instanceof UnresolvedJavaType) {
            // Don't allow unresolved types to hang around forever
            UnresolvedJavaType unresolvedType = (UnresolvedJavaType) currentType;
            ResolvedJavaType resolved = holder.lookupType(unresolvedType, false);
            if (resolved != null) {
                type = resolved;
            }
        }
        return type;
    }

    @Override
    public int getOffset() {
        return offset;
    }

    @Override
    public String toString() {
        return format("HotSpotField<%H.%n %t:") + offset + ">";
    }

    @Override
    public boolean isSynthetic() {
        return (config().jvmAccSynthetic & modifiers) != 0;
    }

    /**
     * Checks if this field has the {@code Stable} annotation.
     *
     * @return true if field has {@code Stable} annotation, false otherwise
     */
    @Override
    public boolean isStable() {
        return (config().jvmAccFieldStable & modifiers) != 0;
    }

    private boolean hasAnnotations() {
        if (!isInternal()) {
            HotSpotVMConfig config = config();
            final long metaspaceAnnotations = UNSAFE.getAddress(holder.getMetaspaceKlass() + config.instanceKlassAnnotationsOffset);
            if (metaspaceAnnotations != 0) {
                long fieldsAnnotations = UNSAFE.getAddress(metaspaceAnnotations + config.annotationsFieldAnnotationsOffset);
                if (fieldsAnnotations != 0) {
                    long fieldAnnotations = UNSAFE.getAddress(fieldsAnnotations + config.fieldsAnnotationsBaseOffset + (ADDRESS_SIZE * index));
                    return fieldAnnotations != 0;
                }
            }
        }
        return false;
    }

    @Override
    public Annotation[] getAnnotations() {
        if (!hasAnnotations()) {
            return new Annotation[0];
        }
        return toJava().getAnnotations();
    }

    @Override
    public Annotation[] getDeclaredAnnotations() {
        if (!hasAnnotations()) {
            return new Annotation[0];
        }
        return toJava().getDeclaredAnnotations();
    }

    @Override
    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        if (!hasAnnotations()) {
            return null;
        }
        return toJava().getAnnotation(annotationClass);
    }

    /**
     * Gets a {@link Field} object corresponding to this object. This method always returns the same
     * {@link Field} object for a given {@link HotSpotResolvedJavaFieldImpl}. This ensures
     * {@link #getDeclaredAnnotations()}, {@link #getAnnotations()} and
     * {@link #getAnnotation(Class)} are stable with respect to the identity of the
     * {@link Annotation} objects they return.
     */
    private Field toJava() {
        synchronized (holder) {
            HashMap<HotSpotResolvedJavaFieldImpl, Field> cache = holder.reflectionFieldCache;
            if (cache == null) {
                cache = new HashMap<>();
                holder.reflectionFieldCache = cache;
            }
            Field reflect = cache.get(this);
            if (reflect == null) {
                reflect = compilerToVM().asReflectionField(holder, index);
                cache.put(this, reflect);
            }
            return reflect;
        }
    }
}
