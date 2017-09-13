/*
 * Copyright 2015-present wequick.net
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.paem.jugg.gradle.small

import com.android.build.api.transform.Format
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.internal.pipeline.IntermediateFolderUtils
import com.android.build.gradle.internal.pipeline.TransformTask
import com.android.build.gradle.internal.transforms.ProGuardTransform
import com.android.build.gradle.tasks.MergeManifests
import com.android.build.gradle.tasks.MergeSourceSetFolders
import com.android.build.gradle.tasks.ProcessAndroidResources
import com.android.build.gradle.tasks.ProcessTestManifest
import com.paem.jugg.gradle.small.transform.StripAarTransform
import com.paem.jugg.gradle.small.utils.AarPath
import com.paem.jugg.gradle.small.utils.Log
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.internal.artifacts.dependencies.DefaultProjectDependency
import org.gradle.api.tasks.compile.JavaCompile

class AppPlugin extends BundlePlugin {

  protected static def sPackageIds = [:] as LinkedHashMap<String, Integer>

  protected Set<Project> mDependentLibProjects
  protected Set<Project> mTransitiveDependentLibProjects
  protected Set<Project> mProvidedProjects
  protected Set<Project> mCompiledProjects
  protected Set<Map> mUserLibAars
  protected Set<File> mLibraryJars
  protected File mMinifyJar

  void apply(Project project) {
    println 'Welcome to AppPlugin!'
    super.apply(project)
  }

  @Override
  protected Class<? extends BaseExtension> getExtensionClass() {
    return AppExtension.class
  }

  @Override
  protected PluginType getPluginType() {
    return PluginType.App
  }

  @Override
  protected void createExtension() {
    super.createExtension()
  }

  @Override
  protected AppExtension getSmall() {
    return super.getSmall()
  }

  @Override
  protected void afterEvaluate(boolean released) {
    super.afterEvaluate(released)
    println ">> AppPlugin.afterEvaluate"

    // Initialize a resource package id for current bundle
    //        initPackageId() // AAPT id 分段

    // Get all dependencies with gradle script `compile project(':lib.*')'
    DependencySet compilesDependencies = project.configurations.compile.dependencies
    Set<DefaultProjectDependency> allLibs = compilesDependencies.withType(
        DefaultProjectDependency.class)
    Set<DefaultProjectDependency> smallLibs = []
    mUserLibAars = []
    mDependentLibProjects = []
    mProvidedProjects = []
    mCompiledProjects = []
    allLibs.each {
      if (rootSmall.isLibProject(it.dependencyProject)) {
        smallLibs.add(it)
        mProvidedProjects.add(it.dependencyProject)
        mDependentLibProjects.add(it.dependencyProject)
        println "Add provided dependency project: ${it.name}"
      } else {
        mCompiledProjects.add(it.dependencyProject)
        collectAarsOfLibrary(it.dependencyProject, mUserLibAars)
        println "Add compile dependency project: ${it.name}"
      }
    }
    collectAarsOfLibrary(project, mUserLibAars)
    mProvidedProjects.addAll(rootSmall.hostStubProjects)

    if (rootSmall.isBuildingLibs()) {
      // While building libs, `lib.*' modules are changing to be an application
      // module and cannot be depended by any other modules. To avoid warnings,
      // remove the `compile project(':lib.*')' dependencies temporary.
      compilesDependencies.removeAll(smallLibs)
    }

    if (!released) return

    // Add custom transformation to split shared libraries
    android.registerTransform(new StripAarTransform())

    resolveReleaseDependencies()
  }

  protected static def getJarName(Project project) {
    def group = project.group
    if (group == project.rootProject.name) group = project.name
    return "$group-${project.version}.jar"
  }

  protected static Set<File> getJarDependencies(Project project) {
    return project.fileTree(dir: 'libs', include: '*.jar').asList()
  }

  protected Set<File> getLibraryJars() {
    if (mLibraryJars != null) return mLibraryJars

    mLibraryJars = new LinkedHashSet<File>()

    // Collect the jars in `build-small/intermediates/small-pre-jar/base'
    def baseJars = project.fileTree(dir: rootSmall.preBaseJarDir, include: ['*.jar'])
    mLibraryJars.addAll(baseJars.files)

    // Collect the jars of `compile project(lib.*)' with absolute file path, fix issue #65
    Set<String> libJarNames = []
    Set<File> libDependentJars = []
    mTransitiveDependentLibProjects.each {
      libJarNames += getJarName(it)
      libDependentJars += getJarDependencies(it)
    }

    if (libJarNames.size() > 0) {
      def libJars = project.files(libJarNames.collect {
        new File(rootSmall.preLibsJarDir, it).path
      })
      mLibraryJars.addAll(libJars.files)
    }

    mLibraryJars.addAll(libDependentJars)

    // Collect stub and small jars in host
    Set<Project> sharedProjects = []
    sharedProjects.addAll(rootSmall.hostStubProjects)
    if (rootSmall.smallProject != null) {
      sharedProjects.add(rootSmall.smallProject)
    }
    sharedProjects.each {
      def jarTask = it.tasks.withType(TransformTask.class).find {
        it.variantName == 'release' && it.transform.name == 'syncLibJars'
      }
      if (jarTask != null) {
        mLibraryJars.addAll(jarTask.otherFileOutputs)
      }
    }

    rootSmall.hostProject.tasks.withType(TransformTask.class).each {
      if ((it.variantName == 'release' ||
          it.variantName.contains("Release")) && (it.transform.name == 'dex' ||
          it.transform.name ==
          'proguard')) {
        mLibraryJars.addAll(it.streamInputs.findAll { it.name.endsWith('.jar') })
      }
    }

    return mLibraryJars
  }

  protected void resolveReleaseDependencies() {
    // Pre-split all the jar dependencies (deep level)
    def compile = project.configurations.compile
    compile.exclude group: 'com.android.support', module: 'support-annotations'
    rootSmall.preLinkJarDir.listFiles().each { file ->
      if (!file.name.endsWith('D.txt')) return
      if (file.name.startsWith(project.name)) return

      file.eachLine { line ->
        def module = line.split(':')
        compile.exclude group: module[0], module: module[1]
      }
    }
  }

  @Override
  protected void hookPreDebugBuild() {
    super.hookPreDebugBuild()

    // If an app.A dependent by lib.B and both of them declare application@name in their
    // manifests, the `processManifest` task will raise a conflict error. To avoid this,
    // modify the lib.B manifest to remove the attributes before app.A `processManifest`
    // and restore it after the task finished.

    // processDebugManifest
    project.tasks.withType(MergeManifests.class).each {
      if (it.variantName.startsWith('release')) return

      if (it.hasProperty('providers')) {
        it.providers = []
        return
      }

      hookProcessDebugManifest(it, it.libraries)
    }

    // processDebugAndroidTestManifest
    project.tasks.withType(ProcessTestManifest.class).each {
      if (it.variantName.startsWith('release')) return

      if (it.hasProperty('providers')) {
        it.providers = []
        return
      }

      hookProcessDebugManifest(it, it.libraries)
    }
  }

  protected void collectLibManifests(def lib, Set outFiles) {
    outFiles.add(lib.getManifest())

    if (lib.hasProperty("libraryDependencies")) {
      // >= 2.2.0
      lib.getLibraryDependencies().each {
        collectLibManifests(it, outFiles)
      }
    } else {
      // < 2.2.0
      lib.getManifestDependencies().each {
        collectLibManifests(it, outFiles)
      }
    }
  }

  /**
   *
   * @param processDebugManifest
   * @param libs
   */
  protected void hookProcessDebugManifest(Task processDebugManifest, List libs) {
    if (processDebugManifest.hasProperty('providers')) {
      processDebugManifest.providers = []
      return
    }

    processDebugManifest.doFirst {
      def libManifests = new HashSet<File>()
      libs.each {
        def components = it.name.split(':')
        // e.g. 'Sample:lib.style:unspecified'
        if (components.size() != 3) return

        def projectName = components[1]
        if (!rootSmall.isLibProject(projectName)) return

        Set<File> allManifests = new HashSet<File>()
        collectLibManifests(it, allManifests)

        libManifests.addAll(allManifests.findAll {
          // e.g.
          // '**/Sample/lib.style/unspecified/AndroidManifest.xml
          // '**/Sample/lib.analytics/unspecified/AndroidManifest.xml
          def name = it.parentFile.parentFile.name
          rootSmall.isLibProject(name)
        })
      }

      def filteredManifests = []
      libManifests.each { File manifest ->
        def sb = new StringBuilder()
        def enteredApplicationNode = false
        def needsFilter = true
        def filtered = false
        manifest.eachLine { line ->
          if (!needsFilter && !filtered) return

          while (true) {
            // fake loop for less `if ... else' statement
            if (!needsFilter) break

            def i = line.indexOf('<application')
            if (i < 0) {
              if (!enteredApplicationNode) break

              if (line.indexOf('>') > 0) needsFilter = false

              // filter `android:name'
              if (line.indexOf('android:name') > 0) {
                filtered = true
                if (needsFilter) return

                line = '>'
              }
              break
            }

            def j = line.indexOf('<!--')
            if (j > 0 && j < i) break // ignores the comment line

            if (line.indexOf('>') > 0) {
              // <application /> or <application .. > in one line
              needsFilter = false
              def k = line.indexOf('android:name="')
              if (k > 0) {
                filtered = true
                def k_ = line.indexOf('"', k + 15)
                // bypass 'android:name='
                line = line.substring(0, k) + line.substring(k_ + 1)
              }
              break
            }

            enteredApplicationNode = true // mark this for next line
            break
          }

          sb.append(line).append(System.lineSeparator())
        }

        if (filtered) {
          def backupManifest = new File(manifest.parentFile, "${manifest.name}~")
          manifest.renameTo(backupManifest)
          manifest.write(sb.toString(), 'utf-8')
          filteredManifests.add(overwrite: manifest, backup: backupManifest)
        }
      }
      ext.filteredManifests = filteredManifests
    }
    processDebugManifest.doLast {
      ext.filteredManifests.each {
        it.backup.renameTo(it.overwrite)
      }
    }
  }

  @Override
  protected void configureReleaseVariant(BaseVariant variant) {
    super.configureReleaseVariant(variant)
    println ">> AppPlugin.configureReleaseVariant"

    // Fill extensions
    def variantName = variant.name.capitalize()
    File mergerDir = variant.mergeResources.incrementalFolder

    small.with {
      javac = variant.javaCompile
      processManifest = project.tasks["process${variantName}Manifest"]

      packageName = variant.applicationId
      packagePath = packageName.replaceAll('\\.', '/')
      classesDir = javac.destinationDir
      bkClassesDir = new File(classesDir.parentFile, "${classesDir.name}~")

      aapt = (ProcessAndroidResources) project.tasks["process${variantName}Resources"]
      apFile = aapt.packageOutputFile

      File symbolDir = aapt.textSymbolOutputDir
      File sourceDir = aapt.sourceOutputDir

      symbolFile = new File(symbolDir, 'R.txt')
      rJavaFile = new File(sourceDir, "${packagePath}/R.java")

      splitRJavaFile = new File(sourceDir.parentFile, "small/${packagePath}/R.java")

      mergerXml = new File(mergerDir, 'merger.xml')
    }

    hookVariantTask(variant)
  }

  @Override
  protected void configureProguard(BaseVariant variant, TransformTask proguard,
      ProGuardTransform pt) {
    super.configureProguard(variant, proguard, pt)

    // Keep R.*
    // FIXME: the `configuration' field is protected, may be depreciated
    pt.configuration.keepAttributes = ['InnerClasses']
    pt.keep("class ${variant.applicationId}.R")
    pt.keep("class ${variant.applicationId}.R\$* { <fields>; }")

    // Add reference libraries
    proguard.doFirst {
      getLibraryJars().findAll { it.exists() }.each {
        // FIXME: the `libraryJar' method is protected, may be depreciated
        pt.libraryJar(it)
      }
    }
    // Split R.class
    proguard.doLast {
      if (small.splitRJavaFile == null || !small.splitRJavaFile.exists()) {
        return
      }

      def minifyJar = IntermediateFolderUtils.getContentLocation(proguard.streamOutputFolder,
          'main', pt.outputTypes, pt.scopes, Format.JAR)
      if (!minifyJar.exists()) return

      mMinifyJar = minifyJar // record for `LibraryPlugin'

      Log.success("[$project.name] Strip aar classes...")

      // Unpack the minify jar to split the R.class
      File unzipDir = new File(minifyJar.parentFile, 'main')
      project.copy {
        from project.zipTree(minifyJar)
        into unzipDir
      }

      def javac = small.javac
      File pkgDir = new File(unzipDir, small.packagePath)

      // Delete the original generated R$xx.class
      pkgDir.listFiles().each { f ->
        if (f.name.startsWith('R$')) {
          f.delete()
        }
      }

      // Re-compile the split R.java to R.class
      project.ant.javac(srcdir: small.splitRJavaFile.parentFile,
          source: javac.sourceCompatibility,
          target: javac.targetCompatibility,
          destdir: unzipDir)

      // Repack the minify jar
      project.ant.zip(baseDir: unzipDir, destFile: minifyJar)

      Log.success "[${project.name}] split R.class..."
    }
  }

  /** Collect the vendor aars (has resources) compiling in current bundle */
  protected void collectVendorAars(Set<ResolvedDependency> outFirstLevelAars,
      Set<Map> outTransitiveAars) {
    project.configurations.compile.resolvedConfiguration.firstLevelModuleDependencies.each {
      collectVendorAars(it, outFirstLevelAars, outTransitiveAars)
    }
  }

  protected boolean collectVendorAars(ResolvedDependency node,
      Set<ResolvedDependency> outFirstLevelAars,
      Set<Map> outTransitiveAars) {
    def group = node.moduleGroup,
        name = node.moduleName,
        version = node.moduleVersion

    if (group == '' && version == '') {
      // Ignores the dependency of local aar
      return false
    }
    if (small.splitAars.find { aar -> group == aar.group && name == aar.name } != null) {
      // Ignores the dependency which has declared in host or lib.*
      return false
    }
    if (small.retainedAars.find { aar -> group == aar.group && name == aar.name } != null) {
      // Ignores the dependency of normal modules
      return false
    }

    String path = "$group/$name/$version"
    def aar = [path: path, group: group, name: node.name, version: version]
    File aarOutput = small.buildCaches.get(path)
    if (aarOutput != null) {
      def resDir = new File(aarOutput, "res")
      // If the dependency has resources, collect it
      if (resDir.exists() && resDir.list().size() > 0) {
        if (outFirstLevelAars != null && !outFirstLevelAars.contains(node)) {
          outFirstLevelAars.add(node)
        }
        if (!outTransitiveAars.contains(aar)) {
          outTransitiveAars.add(aar)
        }
        node.children.each { next -> collectVendorAars(next, null, outTransitiveAars)
        }
        return true
      }
    }

    // Otherwise, check it's children for recursively collecting
    boolean flag = false
    node.children.each { next -> flag |= collectVendorAars(next, null, outTransitiveAars)
    }
    if (!flag) return false

    if (outFirstLevelAars != null && !outFirstLevelAars.contains(node)) {
      outFirstLevelAars.add(node)
    }
    return true
  }

  protected void collectTransitiveAars(ResolvedDependency node,
      Set<ResolvedDependency> outAars) {
    def group = node.moduleGroup,
        name = node.moduleName

    if (small.splitAars.find { aar -> group == aar.group && name == aar.name } == null) {
      outAars.add(node)
    }

    node.children.each {
      collectTransitiveAars(it, outAars)
    }
  }

  /**
   * Prepare retained resource types and resource id maps for package slicing*/

  protected boolean shouldStripInput(File input) {
    AarPath aarPath = new AarPath(project, input)
    for (aar in small.splitAars) {
      if (aarPath.explodedFromAar(aar)) {
        return true
      }
    }
    return false
  }

  protected void hookVariantTask(BaseVariant variant) {
    hookMergeAssets(variant.mergeAssets)

    hookProcessManifest(small.processManifest)

    //        hookAapt(small.aapt)

    hookJavac(small.javac, variant.buildType.minifyEnabled)

    hookKotlinCompile()

    def transformTasks = project.tasks.withType(TransformTask.class)
    def mergeJniLibsTask = transformTasks.find {
      it.transform.name == 'mergeJniLibs' && it.variantName == variant.name
    }
    hookMergeJniLibs(mergeJniLibsTask)

    def mergeJavaResTask = transformTasks.find {
      it.transform.name == 'mergeJavaRes' && it.variantName == variant.name
    }
    hookMergeJavaRes(mergeJavaResTask)

    // Hook clean task to unset package id
    project.clean.doLast {
      sPackageIds.remove(project.name)
    }
  }

  /**
   * Hook merge-jniLibs task to ignores the lib.* native libraries
   * TODO: filter the native libraries while exploding aar*/
  def hookMergeJniLibs(TransformTask t) {
    stripAarFiles(t, { splitPaths ->
      t.streamInputs.each {
        if (shouldStripInput(it)) {
          splitPaths.add(it)
        }
      }
    })
  }

  /**
   * Hook merge-javaRes task to ignores the lib.* jar assets*/
  def hookMergeJavaRes(TransformTask t) {
    stripAarFiles(t, { splitPaths ->
      t.streamInputs.each {
        if (shouldStripInput(it)) {
          splitPaths.add(it)
        }
      }
    })
  }

  /**
   * Hook merge-assets task to ignores the lib.* assets
   * TODO: filter the assets while exploding aar*/
  private void hookMergeAssets(MergeSourceSetFolders t) {
    stripAarFiles(t, { paths ->
      t.inputDirectorySets.each {
        if (it.configName == 'main' || it.configName == 'release') return

        it.sourceFiles.each {
          if (shouldStripInput(it)) {
            paths.add(it)
          }
        }
      }
    })
  }

  /**
   * A hack way to strip aar files:
   *  - Strip the task inputs before the task execute
   *  - Restore the inputs after the task executed
   * by what the task doesn't know what happen, and will be considered as 'UP-TO-DATE'
   * at next time it be called. This means a less I/O.
   * @param t the task who will merge aar files
   * @param closure the function to gather all the paths to be stripped
   */
  private static void stripAarFiles(Task t, Closure closure) {
    t.doFirst {
      List<File> stripPaths = []
      closure(stripPaths)

      Set<Map> strips = []
      stripPaths.each {
        def backup = new File(it.parentFile, "$it.name~")
        strips.add(org: it, backup: backup)
        it.renameTo(backup)
        println "strip aar files backup:${backup.absolutePath}"
      }
      it.extensions.add('strips', strips)
    }
    t.doLast {
      Set<Map> strips = (Set<Map>) it.extensions.getByName('strips')
      strips.each {
        it.backup.renameTo(it.org)
        println "strip aar files rename to org:${it.org}"
      }
    }
  }

  protected static void collectAars(File d, Project src, Set outAars) {
    if (!d.exists()) return

    d.eachLine { line ->
      def module = line.split(':')
      def N = module.size()
      def aar = [group: module[0], name: module[1], version: (N == 3) ? module[2] : '']
      if (!outAars.contains(aar)) {
        outAars.add(aar)
      }
    }
  }

  protected void collectLibProjects(Project project, Set<Project> outLibProjects) {
    DependencySet compilesDependencies = project.configurations.compile.dependencies
    Set<DefaultProjectDependency> allLibs = compilesDependencies.withType(
        DefaultProjectDependency.class)
    allLibs.each {
      def dependency = it.dependencyProject
      if (rootSmall.isLibProject(dependency)) {
        outLibProjects.add(dependency)
        collectLibProjects(dependency, outLibProjects)
      }
    }
  }

  @Override
  protected void hookPreReleaseBuild() {
    super.hookPreReleaseBuild()
    println ">> AppPlugin.hookPreReleaseBuild"

    // Ensure generating text symbols - R.txt
    // --------------------------------------
    def symbolsPath = small.aapt.textSymbolOutputDir.path
    android.aaptOptions.additionalParameters '--output-text-symbols', symbolsPath

    // Resolve dependent AARs
    // ----------------------
    def smallLibAars = new HashSet()
    // the aars compiled in host or lib.*

    // Collect transitive dependent `lib.*' projects
    mTransitiveDependentLibProjects = new HashSet<>()
    mTransitiveDependentLibProjects.addAll(mProvidedProjects)
    mProvidedProjects.each {
      collectLibProjects(it, mTransitiveDependentLibProjects)
    }

    // Collect aar(s) in lib.*
    mTransitiveDependentLibProjects.each { lib -> // lib.* dependencies
      collectAarsOfProject(lib, true, smallLibAars)
    }

    // Collect aar(s) in host
    collectAarsOfProject(rootSmall.hostProject, false, smallLibAars)

    small.splitAars = smallLibAars
    small.retainedAars = mUserLibAars
  }

  protected static def collectAarsOfLibrary(Project lib, HashSet outAars) {
    // lib.* self
    outAars.add(group: lib.group, name: lib.name, version: lib.version)
    // lib.* self for android plugin 2.3.0+
    File dir = lib.projectDir
    outAars.add(group: dir.parentFile.name, name: dir.name, version: lib.version)
  }

  protected def collectAarsOfProject(Project project, boolean isLib, HashSet outAars) {
    String dependenciesFileName = "$project.name-D.txt"

    // Pure aars
    File file = new File(rootSmall.preLinkAarDir, dependenciesFileName)
    collectAars(file, project, outAars)

    // Jar-only aars
    file = new File(rootSmall.preLinkJarDir, dependenciesFileName)
    collectAars(file, project, outAars)

    if (isLib) {
      collectAarsOfLibrary(project, outAars)
    }
  }

  private def hookProcessManifest(Task processManifest) {
    // If an app.A dependent by lib.B and both of them declare application@name in their
    // manifests, the `processManifest` task will raise an conflict error.
    // Cause the release mode doesn't need to merge the manifest of lib.*, simply split
    // out the manifest dependencies from them.
    if (processManifest.hasProperty('providers')) {
      processManifest.providers = []
    } else {
      processManifest.doFirst { MergeManifests it ->
        if (pluginType != PluginType.App) return

        def libs = it.libraries
        def smallLibs = []
        libs.each {
          def components = it.name.split(':')
          // e.g. 'Sample:lib.style:unspecified'
          if (components.size() != 3) return

          def projectName = components[1]
          if (!rootSmall.isLibProject(projectName)) return

          smallLibs.add(it)
        }
        libs.removeAll(smallLibs)
        it.libraries = libs
      }
    }
    // Hook process-manifest task to remove the `android:icon' and `android:label' attribute
    // which declared in the plugin `AndroidManifest.xml' application node. (for #11)
    processManifest.doLast { MergeManifests it ->
      File manifestFile = it.manifestOutputFile
      def sb = new StringBuilder()
      def enteredApplicationNode = false
      def needsFilter = true
      def filterKeys = ['android:icon', 'android:label',
                        'android:allowBackup', 'android:supportsRtl']

      // We don't use XmlParser but simply parse each line cause this should be faster
      manifestFile.eachLine { line ->
        while (true) {
          // fake loop for less `if ... else' statement
          if (!needsFilter) break

          def i = line.indexOf('<application')
          if (i < 0) {
            if (!enteredApplicationNode) break

            int endPos = line.indexOf('>')
            if (endPos > 0) needsFilter = false

            // filter unused keys
            def filtered = false
            filterKeys.each {
              if (line.indexOf(it) > 0) {
                filtered = true
                return
              }
            }
            if (filtered) {
              if (needsFilter) return

              if (line.charAt(endPos - 1) == '/' as char) {
                line = '/>'
              } else {
                line = '>'
              }
            }
            break
          }

          def j = line.indexOf('<!--')
          if (j > 0 && j < i) break // ignores the comment line

          if (line.indexOf('>') > 0) {
            // <application /> or <application .. > in one line
            needsFilter = false
            // Remove all the unused keys, fix #313
            filterKeys.each {
              line = line.replaceAll(" $it=\"[^\"]+\"", "")
            }
            break
          }

          enteredApplicationNode = true // mark this for next line
          break
        }

        sb.append(line).append(System.lineSeparator())
      }
      manifestFile.write(sb.toString(), 'utf-8')
    }
  }


  /**
   * Hook javac task to split libraries' R.class*/
  private def hookJavac(Task javac, boolean minifyEnabled) {
    addClasspath(javac)
    javac.doLast { JavaCompile it ->
      if (minifyEnabled) return // process later in proguard task
      if (!small.splitRJavaFile.exists()) return

      File classesDir = it.destinationDir
      File dstDir = new File(classesDir, small.packagePath)

      // Delete the original generated R$xx.class
      dstDir.listFiles().each { f ->
        if (f.name.startsWith('R$')) {
          f.delete()
        }
      }
      // Re-compile the split R.java to R.class
      project.ant.javac(srcdir: small.splitRJavaFile.parentFile,
          source: it.sourceCompatibility,
          target: it.targetCompatibility,
          destdir: classesDir)

      Log.success "[${project.name}] split R.class..."
    }
  }

  private def hookKotlinCompile() {
    project.tasks.all {
      if (it.name.startsWith('compile')
          && it.name.endsWith('Kotlin')
          && it.hasProperty('classpath')) {
        addClasspath(it)
      }
    }
  }

  private def addClasspath(Task javac) {
    javac.doFirst {
      // Dynamically provided jars
      javac.classpath += project.files(getLibraryJars().findAll { it.exists() })
    }
  }
}
