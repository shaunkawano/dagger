/*
 * Copyright (C) 2015 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen;

import static com.google.auto.common.MoreElements.getLocalAndInheritedMethods;
import static com.google.auto.common.MoreElements.isAnnotationPresent;
import static com.google.common.base.Preconditions.checkArgument;
import static javax.lang.model.element.ElementKind.INTERFACE;

import com.google.auto.common.MoreTypes;
import com.google.auto.value.AutoValue;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import dagger.Module;
import dagger.Multibindings;
import dagger.internal.codegen.BindingType.HasBindingType;
import dagger.internal.codegen.ContributionType.HasContributionType;
import dagger.multibindings.Multibinds;
import dagger.producers.Producer;
import dagger.producers.ProducerModule;
import java.util.Map;
import java.util.Set;
import javax.inject.Provider;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * A declaration that a multibinding with a certain key is available to be injected in a component
 * even if the component has no multibindings for that key. Identified by a map- or set-returning
 * method in a {@link Multibindings @Multibindings}-annotated interface nested within a module.
 */
@AutoValue
abstract class MultibindingDeclaration extends BindingDeclaration
    implements HasBindingType, HasContributionType {

  /**
   * The map or set key whose availability is declared. For maps, this will be {@code Map<K, F<V>>},
   * where {@code F} is either {@link Provider} or {@link Producer}. For sets, this will be
   * {@code Set<T>}.
   */
  @Override
  public abstract Key key();

  /**
   * {@link ContributionType#SET} if the declared type is a {@link Set}, or
   * {@link ContributionType#MAP} if it is a {@link Map}.
   */
  @Override
  public abstract ContributionType contributionType();

  /**
   * {@link BindingType#PROVISION} if the {@link Multibindings @Multibindings}-annotated interface
   * is nested in a {@link Module @Module}, or {@link BindingType#PROVISION} if it is nested in a
   * {@link ProducerModule @ProducerModule}.
   */
  @Override
  public abstract BindingType bindingType();

  /**
   * A factory for {@link MultibindingDeclaration}s.
   */
  static final class Factory {
    private final Elements elements;
    private final Types types;
    private final Key.Factory keyFactory;
    private final TypeElement objectElement;

    Factory(Elements elements, Types types, Key.Factory keyFactory) {
      this.elements = elements;
      this.types = types;
      this.keyFactory = keyFactory;
      this.objectElement = elements.getTypeElement(Object.class.getCanonicalName());
    }

    /**
     * Creates multibinding declarations for each method in a
     * {@link Multibindings @Multibindings}-annotated interface.
     */
    ImmutableSet<MultibindingDeclaration> forMultibindingsInterface(TypeElement interfaceElement) {
      checkArgument(interfaceElement.getKind().equals(INTERFACE));
      checkArgument(isAnnotationPresent(interfaceElement, Multibindings.class));
      BindingType bindingType = bindingType(interfaceElement.getEnclosingElement());
      DeclaredType interfaceType = MoreTypes.asDeclared(interfaceElement.asType());

      ImmutableSet.Builder<MultibindingDeclaration> declarations = ImmutableSet.builder();
      for (ExecutableElement method : getLocalAndInheritedMethods(interfaceElement, elements)) {
        if (!method.getEnclosingElement().equals(objectElement)) {
          ExecutableType methodType =
              MoreTypes.asExecutable(types.asMemberOf(interfaceType, method));
          declarations.add(forDeclaredMethod(bindingType, method, methodType, interfaceElement));
        }
      }
      return declarations.build();
    }

    /** A multibinding declaration for a {@link Multibinds @Multibinds} method. */
    MultibindingDeclaration forMultibindsMethod(
        ExecutableElement moduleMethod, TypeElement moduleElement) {
      checkArgument(isAnnotationPresent(moduleMethod, Multibinds.class));
      return forDeclaredMethod(
          bindingType(moduleElement),
          moduleMethod,
          MoreTypes.asExecutable(
              types.asMemberOf(MoreTypes.asDeclared(moduleElement.asType()), moduleMethod)),
          moduleElement);
    }

    private BindingType bindingType(Element moduleElement) {
      if (isAnnotationPresent(moduleElement, Module.class)) {
        return BindingType.PROVISION;
      } else if (isAnnotationPresent(moduleElement, ProducerModule.class)) {
        return BindingType.PRODUCTION;
      } else {
        throw new IllegalArgumentException(
            "Expected " + moduleElement + " to be a @Module or @ProducerModule");
      }
    }

    private MultibindingDeclaration forDeclaredMethod(
        BindingType bindingType,
        ExecutableElement method,
        ExecutableType methodType,
        TypeElement contributingType) {
      TypeMirror returnType = methodType.getReturnType();
      checkArgument(
          SetType.isSet(returnType) || MapType.isMap(returnType),
          "%s must return a set or map",
          method);
      return new AutoValue_MultibindingDeclaration(
          Optional.<Element>of(method),
          Optional.of(contributingType),
          keyFactory.forMultibindsMethod(bindingType, methodType, method),
          contributionType(returnType),
          bindingType);
    }

    private ContributionType contributionType(TypeMirror returnType) {
      if (MapType.isMap(returnType)) {
        return ContributionType.MAP;
      } else if (SetType.isSet(returnType)) {
        return ContributionType.SET;
      } else {
        throw new IllegalArgumentException("Must be Map or Set: " + returnType);
      }
    }
  }
}
