package org.hitachivantara.ci.utils

import org.junit.rules.ExternalResource
import org.junit.rules.TestRule

/**
 * Replaces the property on the given object returning it to its original value after the test.
 * Same as {@link org.spockframework.runtime.extension.builtin.ConfineMetaClassChangesInterceptor},
 * but for use in a {@link TestRule}.
 */
class ReplacePropertyRule extends ExternalResource {
  Map<Class<?>, Map<String, Closure>> replacements

  private final Set<Class<?>> classes
  private final MetaClassRegistry registry = GroovySystem.getMetaClassRegistry()
  private final List<MetaClass> originalMetaClasses = []

  ReplacePropertyRule() {
    this.classes = []
    this.replacements = [:]
  }

  ReplacePropertyRule(Map<Class<?>, Map<String, Closure>> replacements) {
    this.classes = new LinkedHashSet<>(replacements.keySet())
    this.replacements = replacements
  }
  /**
   * Add replacement for inline tests
   * @param clazz
   * @param replacement
   */
  void addReplacement(Class<?> clazz, Map<String, Closure> replacement) {
    addReplacements([(clazz): replacement])
  }

  void addReplacements(Map<Class<?>, Map<String, Closure>> replacements) {
    Set<Class<?>> classes = replacements.keySet()
    this.classes.addAll(classes)
    this.replacements.putAll(replacements)

    saveOriginals(classes)
    replaceProperties()
  }

  private void saveOriginals(Set<Class<?>> classes) {
    for (Class<?> clazz : classes) {
      originalMetaClasses.add(registry.getMetaClass(clazz))
      MetaClass temporaryMetaClass = new ExpandoMetaClass(clazz, true, true)
      temporaryMetaClass.initialize()
      registry.setMetaClass(clazz, temporaryMetaClass)
    }
  }

  @Override
  protected void before() throws Throwable {
    saveOriginals(classes)
    replaceProperties()
  }

  @Override
  protected void after() {
    originalMetaClasses.each { MetaClass original ->
      registry.setMetaClass(original.getTheClass(), original)
    }
    originalMetaClasses.clear()
  }

  void replaceProperties() {
    replacements.each { Class<?> clazz, Map<String, Closure> replacement ->
      replacement.each { String property, Closure c ->
        def expando = clazz.metaClass
        List<String> fields = property.tokenize('.')
        String method = fields.last()
        (fields-method).each {
          expando = expando[it]
        }
        expando[method] = c
      }
    }
  }
}
