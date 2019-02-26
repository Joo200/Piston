/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) EngineHub <http://www.enginehub.com>
 * Copyright (C) oblique-commands contributors
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

package com.enginehub.piston;

import com.google.common.collect.ImmutableList;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeSpec;

import javax.annotation.processing.Filer;
import javax.inject.Inject;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

/**
 * Class that handles the generation of command registration classes.
 *
 * <p>
 * These classes are named {@code [CommandContainer class name] + "Registration"}. They are
 * guaranteed to have one method with signature {@code public void register(CommandManager)}.
 * Generated methods will be used to convert the annotation-based configuration into a runtime
 * configuration, allowing for type-safe and efficient implementation while maintaining the ease
 * of annotation-based configuration.
 * </p>
 */
class CommandRegistrationGenerator {
    private final String name;
    private final List<CommandInfo> info;

    CommandRegistrationGenerator(String name, List<CommandInfo> info) {
        this.name = name;
        this.info = ImmutableList.copyOf(info);
    }

    private Stream<RequiredVariable> getRequiredVars() {
        return info.stream().flatMap(info -> info.getRequiredVariables().stream());
    }

    public void generate(Element originalElement, String pkgName, Filer filer) throws IOException {
        TypeSpec.Builder spec = TypeSpec.classBuilder(name)
            .addOriginatingElement(originalElement);

        spec.addFields(generateFields());
        spec.addMethod(generateConstructor());

        JavaFile.builder(pkgName, spec.build())
            .indent("    ")
            .addFileComment("Generated by $L on $L", getClass().getName(), Instant.now().toString())
            .build()
            .writeTo(filer);
    }

    private Iterable<FieldSpec> generateFields() {
        return getRequiredVars()
            .map(var -> FieldSpec.builder(
                var.getType(), var.getName(),
                Modifier.PRIVATE, Modifier.FINAL
            ).build())
            .collect(toList());
    }

    private MethodSpec generateConstructor() {
        List<ParameterSpec> params = getRequiredVars()
            .map(var -> ParameterSpec.builder(var.getType(), var.getName())
                .addAnnotations(var.getAnnotations())
                .build())
            .collect(toList());
        CodeBlock body = getRequiredVars()
            .map(var -> CodeBlock.of("this.$1L = $1L;\n", var.getName()))
            .reduce(CodeBlock.of(""), (a, b) -> a.toBuilder().add(b).build());
        return MethodSpec.constructorBuilder()
            .addAnnotation(Inject.class)
            .addParameters(params)
            .addCode(body)
            .build();
    }
}
