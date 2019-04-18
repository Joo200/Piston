/*
 * Piston, a flexible command management system.
 * Copyright (C) EngineHub <http://www.enginehub.com>
 * Copyright (C) Piston contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.enginehub.piston.gen;

import com.google.common.collect.ImmutableList;
import com.google.inject.Key;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import org.enginehub.piston.CommandManager;
import org.enginehub.piston.CommandParameters;
import org.enginehub.piston.gen.util.CodeBlockUtil;
import org.enginehub.piston.gen.util.SafeName;
import org.enginehub.piston.gen.value.CommandInfo;
import org.enginehub.piston.gen.value.CommandParamInfo;
import org.enginehub.piston.gen.value.ExtractSpec;
import org.enginehub.piston.gen.value.RegistrationInfo;
import org.enginehub.piston.gen.value.RequiredVariable;
import org.enginehub.piston.gen.value.ReservedNames;
import org.enginehub.piston.part.CommandParts;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Streams.concat;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.STATIC;
import static org.enginehub.piston.gen.util.CodeBlockUtil.listForGen;
import static org.enginehub.piston.gen.util.CodeBlockUtil.stringListForGen;

/**
 * Class that handles the generation of command registration classes.
 *
 * <p>
 * These classes are named {@code [CommandContainer class name] + "Registration"}.
 * Generated methods will be used to convert the annotation-based configuration into a runtime
 * configuration, allowing for type-safe and efficient implementation while maintaining the ease
 * of annotation-based configuration.
 * </p>
 */
class CommandRegistrationGenerator {
    private static final ParameterSpec COMMAND_PARAMETERS_SPEC
        = ParameterSpec.builder(CommandParameters.class, "parameters").build();
    public static final RequiredVariable LISTENERS_REQ_VAR = RequiredVariable.builder()
        .name(ReservedNames.LISTENERS)
        .type(ParameterizedTypeName.get(
            ImmutableList.class,
            CommandCallListener.class
        ))
        .build();
    private final RegistrationInfo info;
    private final ImmutableList<RequiredVariable> injectedVariables;

    CommandRegistrationGenerator(RegistrationInfo info) {
        this.info = info;
        this.injectedVariables = concat(
            additionalVariables(info),
            info.getInjectedVariables().stream()
        ).collect(toImmutableList());
    }

    private <T> Stream<T> cmdsFlatMap(Function<CommandInfo, Stream<T>> map) {
        return info.getCommands().stream().flatMap(map);
    }

    private static Stream<RequiredVariable> additionalVariables(RegistrationInfo info) {
        Stream.Builder<RequiredVariable> b = Stream.builder();
        b.add(RequiredVariable.builder()
            .type(ClassName.get(CommandManager.class))
            .name(ReservedNames.COMMAND_MANAGER)
            .build());
        b.add(RequiredVariable.builder()
            .type(info.getTargetClassName())
            .name(ReservedNames.CONTAINER_INSTANCE)
            .build());
        return b.build();
    }

    private Stream<RequiredVariable> getInjectedVariables() {
        return injectedVariables.stream();
    }

    private static boolean isCommandStatic(CommandInfo info) {
        return info.getCommandMethod().getModifiers().contains(STATIC);
    }

    private Modifier[] getApiVisibilityModifiers() {
        return info.getClassVisibility() == null
            ? new Modifier[0]
            : new Modifier[] {info.getClassVisibility()};
    }

    private ClassName getThisClass() {
        return info.getTargetClassName().peerClass(info.getName());
    }

    void generate(Element originalElement, String pkgName, Filer filer) throws IOException {
        TypeSpec.Builder spec = TypeSpec.classBuilder(info.getName())
            .addOriginatingElement(originalElement)
            .addModifiers(FINAL)
            .addModifiers(getApiVisibilityModifiers())
            .addSuperinterface(ParameterizedTypeName.get(
                ClassName.get(CommandRegistration.class),
                info.getTargetClassName()
            ));

        boolean hasSuperClass = false;
        for (TypeElement superType : info.getSuperTypes()) {
            if (superType.getKind() == ElementKind.CLASS) {
                checkState(!hasSuperClass, "Super class already present");
                hasSuperClass = true;
                spec.superclass(TypeName.get(superType.asType()));
            } else if (superType.getKind() == ElementKind.INTERFACE) {
                spec.addSuperinterface(TypeName.get(superType.asType()));
            } else {
                throw new IllegalStateException("Not a possible super-type: "
                    + superType.getKind() + " " + superType.getQualifiedName().toString());
            }
        }

        spec.addFields(generateFields());
        spec.addMethod(generateConstructor());
        // static methods
        spec.addMethod(generateNewBuilderMethod());
        spec.addMethod(generateRequireOptionalMethod());
        spec.addMethod(generateGetCommandMethod());

        // instance methods
        spec.addMethods(generateBuilderSetMethods());
        spec.addMethod(generateBuildMethod());
        spec.addMethods(generateCommandBindings());
        spec.addMethods(getParameterMethods());

        JavaFile.builder(pkgName, spec.build())
            .indent("    ")
            .addFileComment("Generated by $L on $L", getClass().getName(), Instant.now().toString())
            .addStaticImport(CommandParts.class, "flag", "arg")
            .build()
            .writeTo(filer);
    }

    private MethodSpec generateConstructor() {
        return MethodSpec.constructorBuilder()
            .addModifiers(PRIVATE)
            .addStatement("this.$L = $T.of()",
                ReservedNames.LISTENERS, ImmutableList.class)
            .build();
    }

    private MethodSpec.Builder setSpec(RequiredVariable var) {
        return MethodSpec.methodBuilder(var.getName())
            .addModifiers(getApiVisibilityModifiers())
            .returns(getThisClass());
    }

    private Iterable<MethodSpec> generateBuilderSetMethods() {
        Stream<MethodSpec> injectedVariableSets = getInjectedVariables()
            .map(var ->
                setSpec(var)
                    .addParameter(ParameterSpec.builder(var.getType(), var.getName())
                        .addAnnotations(var.getAnnotations())
                        .build())
                    .addStatement("this.$1L = $1L", var.getName())
                    .addStatement("return this")
                    .build()
            );
        Stream<MethodSpec> customSets = Stream.of(
            setSpec(LISTENERS_REQ_VAR)
                .addParameter(ParameterizedTypeName.get(
                    Collection.class,
                    CommandCallListener.class
                ), LISTENERS_REQ_VAR.getName())
                .addStatement("this.$1L = $2T.copyOf($1L)",
                    LISTENERS_REQ_VAR.getName(), ImmutableList.class)
                .addStatement("return this")
                .build()
        );
        return concat(
            injectedVariableSets,
            customSets
        ).collect(toList());
    }

    private Iterable<MethodSpec> getParameterMethods() {
        return cmdsFlatMap(cmd -> cmd.getParams().stream())
            .map(param -> {
                ExtractSpec spec = param.getExtractSpec();
                return MethodSpec.methodBuilder(spec.getName())
                    .addModifiers(PRIVATE)
                    .addParameter(CommandParameters.class, ReservedNames.PARAMETERS)
                    .returns(spec.getType())
                    .addCode(spec.getExtractMethodBody().generate(param.getName()))
                    .build();
            })
            .collect(toSet());
    }

    private Iterable<FieldSpec> generateFields() {
        Stream<FieldSpec> staticFields = getKeyTypeFields();
        Stream<FieldSpec> instanceFields = concat(
            getInjectedVariables(),
            info.getDeclaredFields().stream(),
            Stream.of(LISTENERS_REQ_VAR)
        ).map(var -> FieldSpec.builder(
            var.getType(), var.getName(),
            PRIVATE
        ).build());
        Stream<FieldSpec> partFields = getPartFields();
        return concat(staticFields, instanceFields, partFields).collect(toList());
    }

    private Stream<FieldSpec> getKeyTypeFields() {
        return info.getKeyTypes().stream()
            .map(keyInfo -> {
                TypeName keyType = keyInfo.typeName();
                ParameterizedTypeName keyPType = ParameterizedTypeName.get(
                    ClassName.get(Key.class),
                    keyType.box()
                );
                // _technically_ the spec can only be applied to parameters
                // so we'll whip up a fake inner method with the annotation
                // and at runtime, reflect out the instance
                CodeBlock constrParams = CodeBlock.of("");
                AnnotationSpec annotationSpec = keyInfo.annotationSpec();
                if (annotationSpec != null) {
                    TypeSpec annoExtractor = TypeSpec.anonymousClassBuilder("")
                        // `a` = "annotation", extracts the annotation from its own parameter `ah`
                        .addMethod(MethodSpec.methodBuilder("a")
                            .returns(Annotation.class)
                            // `ah` = "annotation holder", holds the annotation on this parameter
                            .addParameter(ParameterSpec.builder(Object.class, "ah")
                                .addAnnotation(annotationSpec)
                                .build())
                            .addStatement(
                                // from this class
                                "return getClass()\n" +
                                // retrieve this method (there's only one)
                                ".getDeclaredMethods()[0]\n" +
                                // and get its first parameter's first annotation (again, only one)
                                ".getParameterAnnotations()[0][0]")
                            .build())
                        .build();
                    // call the method with a null parameter, since it doesn't really matter
                    constrParams = CodeBlock.of("$L.a(null)", annoExtractor);
                }
                return FieldSpec.builder(
                    keyPType, SafeName.getNameAsIdentifier(keyType) + "Key",
                    PRIVATE, STATIC, FINAL
                ).initializer("$L", TypeSpec.anonymousClassBuilder(constrParams)
                    .superclass(keyPType)
                    .build()
                ).build();
            });
    }

    private Stream<FieldSpec> getPartFields() {
        return cmdsFlatMap(c -> c.getParams().stream())
            .filter(p -> p.getType() != null && p.getName() != null && p.getConstruction() != null)
            .distinct()
            .map(p -> FieldSpec.builder(
                p.getType(), p.getName(),
                PRIVATE, FINAL)
                .initializer(CodeBlock.of("$[$L$]", p.getConstruction()))
                .build());
    }

    private MethodSpec generateBuildMethod() {
        MethodSpec.Builder build = MethodSpec.methodBuilder("build");
        if (info.getClassVisibility() != null) {
            build.addModifiers(info.getClassVisibility());
        }

        for (CommandInfo cmd : info.getCommands()) {
            build.addCode(generateRegisterCommandCode(cmd));
        }
        return build.build();
    }

    private CodeBlock generateRegisterCommandCode(CommandInfo cmd) {
        // Workaround no `addStatement` for now, see:
        // https://github.com/square/javapoet/issues/711
        return CodeBlock.builder()
            .add("$L.register($S, $L);\n", ReservedNames.COMMAND_MANAGER, cmd.getName(),
                generateRegistrationLambda(cmd))
            .build();
    }

    private CodeBlock generateRegistrationLambda(CommandInfo cmd) {
        CodeBlock.Builder lambda = CodeBlock.builder()
            .add("b -> {\n").indent();
        lambda.addStatement("b.aliases($L)", stringListForGen(cmd.getAliases().stream()));
        lambda.addStatement("b.description($S)", cmd.getDescription());
        cmd.getFooter().ifPresent(footer ->
            lambda.addStatement("b.footer($S)", footer)
        );
        lambda.addStatement("b.parts($L)",
            listForGen(cmd.getParams().stream()
                .map(CommandParamInfo::getName)
                .filter(Objects::nonNull)
                .map(name -> CodeBlock.of("$L", name))));
        lambda.addStatement("b.action(this::$L)", SafeName.from(cmd.getName()));
        cmd.getCondition().ifPresent(cond -> {
            lambda.add(cond.getConstruction());
            lambda.addStatement("b.condition($L)", cond.getCondVariable());
        });
        return lambda.unindent().add("}").build();
    }

    private Iterable<MethodSpec> generateCommandBindings() {
        return info.getCommands().stream()
            .map(this::generateCommandBinding)
            .collect(toList());
    }

    private MethodSpec generateCommandBinding(CommandInfo commandInfo) {
        String safeCmdName = SafeName.from(commandInfo.getName());
        MethodSpec.Builder spec = MethodSpec.methodBuilder(safeCmdName)
            .addModifiers(PRIVATE)
            .returns(int.class)
            .addParameter(COMMAND_PARAMETERS_SPEC);
        for (TypeMirror thrownType : commandInfo.getCommandMethod().getThrownTypes()) {
            spec.addException(TypeName.get(thrownType));
        }

        CodeBlock.Builder body = CodeBlock.builder();

        // grab the command method
        body.add(CodeBlockUtil.scopeCommandMethod(commandInfo.getCommandMethod(), "cmdMethod"));

        // call beforeCall
        body.addStatement("$L.forEach(l -> l.beforeCall($L, $L))",
            ReservedNames.LISTENERS, "cmdMethod", ReservedNames.PARAMETERS);

        // open up a `try` for calling after* listeners
        body.beginControlFlow("try");
        body.addStatement("$T result", int.class);

        CodeBlock callCommandMethod = generateCallCommandMethod(commandInfo);
        TypeName rawReturnType = TypeName.get(commandInfo.getCommandMethod().getReturnType()).unbox();
        if (TypeName.INT.equals(rawReturnType)) {
            // call the method, return what it does
            body.addStatement("result = $L", callCommandMethod);
        } else {
            // call the method, return 1
            body.addStatement(callCommandMethod)
                .addStatement("result = 1");
        }

        // call afterCall
        body.addStatement("$L.forEach(l -> l.afterCall($L, $L))",
            ReservedNames.LISTENERS, "cmdMethod", ReservedNames.PARAMETERS)
            .addStatement("return result");

        body.nextControlFlow("catch ($T t)", Throwable.class);

        // call afterThrow & re-throw
        body.addStatement("$L.forEach(l -> l.afterThrow($L, $L, $L))",
            ReservedNames.LISTENERS, "cmdMethod", ReservedNames.PARAMETERS, "t")
            .addStatement("throw t");

        body.endControlFlow();

        spec.addCode(body.build());

        return spec.build();
    }

    private CodeBlock generateCallCommandMethod(CommandInfo commandInfo) {
        CodeBlock target;
        if (commandInfo.getCommandMethod().getModifiers().contains(STATIC)) {
            // call it statically
            target = CodeBlock.of("$T", info.getTargetClassName());
        } else {
            // use the instance
            target = CodeBlock.of("$L", ReservedNames.CONTAINER_INSTANCE);
        }

        String args = commandInfo.getParams().stream()
            .map(param -> CodeBlock.of("this.$L($L)",
                param.getExtractSpec().getName(),
                ReservedNames.PARAMETERS).toString())
            .collect(joining(", "));
        return CodeBlock.of("$L.$L($L)",
            target,
            commandInfo.getCommandMethod().getSimpleName(),
            args);
    }

    private MethodSpec generateNewBuilderMethod() {
        ClassName thisClass = getThisClass();
        return MethodSpec.methodBuilder(ReservedNames.BUILDER)
            .addModifiers(getApiVisibilityModifiers())
            .addModifiers(STATIC)
            .returns(thisClass)
            .addStatement("return new $T()", thisClass)
            .build();
    }

    private MethodSpec generateGetCommandMethod() {
        return MethodSpec.methodBuilder(ReservedNames.GET_COMMAND_METHOD)
            .addModifiers(PRIVATE, STATIC)
            .returns(Method.class)
            .addParameter(String.class, "methodName")
            .addParameter(Class[].class, "parameterTypes")
            .varargs()
            .addCode(CodeBlock.builder()
                .beginControlFlow("try")
                .addStatement(
                    "return $T.class.getDeclaredMethod(methodName, parameterTypes)",
                    info.getTargetClassName())
                .nextControlFlow("catch ($T e)", NoSuchMethodException.class)
                .addStatement(
                    "throw new $T($S + methodName)",
                    IllegalStateException.class, "Missing command method: ")
                .endControlFlow()
                .build())
            .build();
    }

    private MethodSpec generateRequireOptionalMethod() {
        TypeVariableName containedType = TypeVariableName.get("T");
        return MethodSpec.methodBuilder(ReservedNames.REQUIRE_OPTIONAL)
            .addModifiers(PRIVATE, STATIC)
            .addTypeVariable(containedType)
            .returns(containedType)
            .addParameter(ParameterizedTypeName.get(
                ClassName.get(Optional.class),
                containedType
            ), "optional")
            .addParameter(String.class, "name")
            .addStatement("return optional.orElseThrow(() -> new $T($S + name))",
                IllegalStateException.class, "No injected value for ")
            .build();
    }

}
