package org.taumc.celeritas.compat.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyRendererAccessorContractTest {
    private static final String ACCESSOR_ANNOTATION =
            "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER_ANNOTATION =
            "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String MIXIN_CLASS =
            "org/taumc/celeritas/compat/mixin/LegacyRendererAccessMixin.class";
    private static final String TARGET_CLASS =
            "com/dhj/actinium/render/terrain/compile/pipeline/VintageBlockRenderer.class";
    private static final String BRIDGE_RENDERER_CLASS =
            "org/taumc/celeritas/impl/render/terrain/compile/pipeline/VintageBlockRenderer.class";
    private static final String BRIDGE_QUAD_LIST_FACADE = "renderQuadList";

    @Test
    void accessorDescriptorsExactlyMatchRendererFields() throws IOException {
        ClassNode mixin = readClass(MIXIN_CLASS);
        ClassNode target = readClass(TARGET_CLASS);
        Map<String, String> targetFields = new HashMap<>();
        int checkedAccessors = 0;
        for (FieldNode field : target.fields) {
            targetFields.put(field.name, field.desc);
        }

        for (MethodNode method : mixin.methods) {
            AnnotationNode accessor = findAnnotation(method.visibleAnnotations, ACCESSOR_ANNOTATION);
            if (accessor == null) {
                continue;
            }

            checkedAccessors++;
            String fieldName = annotationStringValue(accessor, "value");
            String fieldDescriptor = targetFields.get(fieldName);
            assertNotNull(fieldDescriptor, "Missing target field " + fieldName);
            assertEquals(fieldDescriptor, accessorFieldDescriptor(method),
                    method.name + " must use the exact descriptor of " + fieldName);
        }
        assertTrue(checkedAccessors > 0, "The renderer bridge must declare Accessors");
    }

    @Test
    void invokerDescriptorsExactlyMatchRendererMethods() throws IOException {
        ClassNode mixin = readClass(MIXIN_CLASS);
        ClassNode target = readClass(TARGET_CLASS);
        Map<String, List<String>> targetMethods = new HashMap<>();
        int checkedInvokers = 0;
        for (MethodNode method : target.methods) {
            targetMethods.computeIfAbsent(method.name, ignored -> new ArrayList<>()).add(method.desc);
        }

        for (MethodNode method : mixin.methods) {
            AnnotationNode invoker = findAnnotation(method.visibleAnnotations, INVOKER_ANNOTATION);
            if (invoker == null) {
                continue;
            }

            checkedInvokers++;
            String targetName = annotationStringValue(invoker, "value");
            List<String> descriptors = targetMethods.get(targetName);
            assertNotNull(descriptors, "Missing target method " + targetName);
            assertTrue(descriptors.contains(method.desc),
                    method.name + " must use an exact descriptor of " + targetName);
        }
        assertTrue(checkedInvokers > 0, "The renderer bridge must declare Invokers");
    }

    /**
     * The woven invoker dispatches by name and descriptor. If the bridge renderer declared a
     * method with the same name and descriptor as an invoker target, the dispatch could select
     * the bridge's delegating method instead of the main implementation, recursing until a
     * {@link StackOverflowError} (#138).
     */
    @Test
    void invokerTargetsMustNotCollideWithBridgeRendererMethods() throws IOException {
        ClassNode mixin = readClass(MIXIN_CLASS);
        ClassNode bridgeRenderer = readClass(BRIDGE_RENDERER_CLASS);
        Set<String> bridgeMethods = new HashSet<>();
        for (MethodNode method : bridgeRenderer.methods) {
            bridgeMethods.add(method.name + method.desc);
        }

        int checkedInvokers = 0;
        for (MethodNode method : mixin.methods) {
            AnnotationNode invoker = findAnnotation(method.visibleAnnotations, INVOKER_ANNOTATION);
            if (invoker == null) {
                continue;
            }

            checkedInvokers++;
            String targetName = annotationStringValue(invoker, "value");
            assertFalse(bridgeMethods.contains(targetName + method.desc),
                    "Invoker target " + targetName + " collides with a bridge renderer method; "
                            + "the woven invoker can dispatch back into the delegating method and recurse (#138)");
        }
        assertTrue(checkedInvokers > 0, "The renderer bridge must declare Invokers");
    }

    /**
     * Recursion safety additionally relies on both sides of the quad-list delegation staying
     * private: private methods never override, so the woven invoker always reaches the main
     * implementation directly (#138).
     */
    @Test
    void quadListDispatchStaysPrivateOnBothSides() throws IOException {
        ClassNode main = readClass(TARGET_CLASS);
        ClassNode bridgeRenderer = readClass(BRIDGE_RENDERER_CLASS);

        MethodNode invokerTarget = null;
        for (MethodNode method : main.methods) {
            if (method.name.equals("renderQuadListInternal")) {
                invokerTarget = method;
                break;
            }
        }
        assertNotNull(invokerTarget, "Main renderer must keep the internal quad-list implementation");

        MethodNode bridgeFacade = null;
        for (MethodNode method : bridgeRenderer.methods) {
            if (method.name.equals(BRIDGE_QUAD_LIST_FACADE)) {
                bridgeFacade = method;
                break;
            }
        }
        assertNotNull(bridgeFacade, "Bridge renderer must keep the legacy " + BRIDGE_QUAD_LIST_FACADE + " facade");

        assertTrue((invokerTarget.access & Opcodes.ACC_PRIVATE) != 0,
                "renderQuadListInternal must stay private so it can never be overridden (#138)");
        assertTrue((bridgeFacade.access & Opcodes.ACC_PRIVATE) != 0,
                "The bridge " + BRIDGE_QUAD_LIST_FACADE + " facade must stay private (#138)");
    }

    private static String accessorFieldDescriptor(MethodNode method) {
        Type methodType = Type.getMethodType(method.desc);
        Type returnType = methodType.getReturnType();
        if (returnType.getSort() != Type.VOID) {
            assertEquals(0, methodType.getArgumentTypes().length,
                    method.name + " is an invalid getter Accessor");
            return returnType.getDescriptor();
        }

        assertEquals(1, methodType.getArgumentTypes().length,
                method.name + " is an invalid setter Accessor");
        return methodType.getArgumentTypes()[0].getDescriptor();
    }

    private static AnnotationNode findAnnotation(List<AnnotationNode> annotations, String descriptor) {
        if (annotations == null) {
            return null;
        }
        return annotations.stream()
                .filter(annotation -> annotation.desc.equals(descriptor))
                .findFirst()
                .orElse(null);
    }

    private static String annotationStringValue(AnnotationNode annotation, String key) {
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (annotation.values.get(index).equals(key)) {
                return (String) annotation.values.get(index + 1);
            }
        }
        throw new AssertionError("Missing annotation value " + key);
    }

    private static ClassNode readClass(String resourceName) throws IOException {
        ClassLoader classLoader = LegacyRendererAccessorContractTest.class.getClassLoader();
        try (InputStream stream = classLoader.getResourceAsStream(resourceName)) {
            assertNotNull(stream, "Missing compiled class " + resourceName);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
                    | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
