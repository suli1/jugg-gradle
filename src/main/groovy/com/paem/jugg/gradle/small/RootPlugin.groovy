package com.paem.jugg.gradle.small

import com.paem.jugg.gradle.small.support.KotlinCompat
import com.paem.jugg.gradle.small.tasks.CleanBundleTask
import com.paem.jugg.gradle.small.utils.DependenciesUtils
import com.paem.jugg.gradle.small.utils.Log
import com.paem.jugg.gradle.small.utils.SymbolParser
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.TaskState

import java.text.DecimalFormat

class RootPlugin extends BasePlugin {

  private int buildingLibIndex = 0
  private Map<String, Set<String>> bundleModules = [:]

  void apply(Project project) {
    println "Welcome to RootPlugin!"
    super.apply(project)
  }

  @Override
  protected Class<? extends BaseExtension> getExtensionClass() {
    return RootExtension.class
  }

  RootExtension getSmall() {
    return (RootExtension) project.small
  }

  @Override
  protected void configureProject() {
    super.configureProject()

    injectBuildLog()

    def rootExt = small

    rootExt.appProjects = new HashSet<>()
    rootExt.libProjects = new HashSet<>()
    rootExt.hostStubProjects = new HashSet<>()
    AppPlugin.sPackageIds = [:]

    project.afterEvaluate {
      println "RootPlugin project after evaluate"

      def userBundleTypes = [:]
      rootExt.bundleModules.each { type, names ->
        names.each {
          userBundleTypes.put(it, type)
        }
      }

      // Configure versions
      def base = rootExt.android
      if (base != null) {
        project.subprojects { p ->
          p.afterEvaluate {
            configVersions(p, base)
          }
        }
      }

      // Configure sub projects
      project.subprojects {
        println "sub project ${it.name}"
        if (it.name == 'small') {
          rootExt.smallProject = it
          return
        }

        if (it.name == rootExt.hostModuleName) {
          // Host
          it.apply plugin: HostPlugin
          rootExt.outputBundleDir = new File(it.projectDir, SMALL_LIBS)
          rootExt.hostProject = it
          println "Add HostPlugin to ${rootExt.hostModuleName}"
        } else if (it.name.startsWith('app+')) {
          rootExt.hostStubProjects.add(it)
          return
        } else {
          String type = userBundleTypes.get(it.name)
          if (type == null) {
            def idx = it.name.indexOf('.')
            if (idx < 0) return

            char c = it.name.charAt(idx + 1)
            if (c.isDigit()) {
              // This might be a local aar module composed by name and version
              // as 'feature-1.1.0'
              return
            }

            type = it.name.substring(0, idx)
          }

          switch (type) {
            case 'plugin':
              if (it.plugins.hasPlugin("com.android.application")) {
                it.apply plugin: AppPlugin
                rootExt.appProjects.add(it)
              } else {
                it.apply plugin: LibraryPlugin
                rootExt.libProjects.add(it)
              }
              break;
            case 'stub':
              rootExt.hostStubProjects.add(it)
              return;
            case 'lib':
              it.apply plugin: LibraryPlugin
              rootExt.libProjects.add(it)
              break;
            case 'web':
            default: // Default to Asset
              it.apply plugin: AssetPlugin
              break;
          }

          // Collect for log
          def modules = bundleModules.get(type)
          if (modules == null) {
            modules = new HashSet<String>()
            bundleModules.put(type, modules)
          }
          modules.add(it.name)
        }

        if (it.hasProperty('buildLib')) {
          it.small.buildIndex = ++rootExt.libCount
          it.tasks['buildLib'].doLast {
            buildLib(it.project)
          }
        } else if (it.hasProperty('buildBundle')) {
          it.small.buildIndex = ++rootExt.bundleCount
        }
      }

      if (rootExt.hostProject == null) {
        throw new RuntimeException(
            "Cannot find host module with name: '${rootExt.hostModuleName}'!")
      }

      if (!rootExt.hostStubProjects.empty) {
        rootExt.hostStubProjects.each { stub ->
          rootExt.hostProject.afterEvaluate {
            it.dependencies.add('compile', stub)
          }
          rootExt.appProjects.each {
            it.afterEvaluate {
              it.dependencies.add('compile', stub)
            }
          }
          rootExt.libProjects.each {
            it.afterEvaluate {
              it.dependencies.add('compile', stub)
            }
          }

          stub.task('cleanLib', type: CleanBundleTask)
        }
      }
    }

    compatVendors()
  }

  /**
   *
   * 处理Android APP module的support版本
   * 使所有的support依赖版本保持一致*/
  protected void configVersions(Project p, RootExtension.AndroidConfig base) {
    if (!p.hasProperty('android')) return

    com.android.build.gradle.BaseExtension android = p.android
    if (base.compileSdkVersion != 0) {
      android.compileSdkVersion = base.compileSdkVersion
    }
    if (base.buildToolsVersion != null) {
      android.buildToolsVersion = base.buildToolsVersion
    }
    if (base.supportVersion != null) {
      def sv = base.supportVersion
      def cfg = p.configurations.compile
      def supportDependencies = []
      cfg.dependencies.each { d ->
        if (d.group != 'com.android.support') return
        if (d.name == 'multidex') return
        if (d.version == sv) return

        supportDependencies.add(d)
      }
      cfg.dependencies.removeAll(supportDependencies)
      supportDependencies.each {
        d -> p.dependencies.add('compile', "$d.group:$d.name:$sv")
      }
    }
  }

  @Override
  protected void createTask() {
    super.createTask()
    project.task('cleanLib', group: 'small', description: 'Clean all libraries', type: Delete) {
      delete small.preBuildDir
    }
    project.task('buildLib', group: 'small', description: 'Build all libraries').doFirst {
      buildingLibIndex = 1
    }
    project.task('cleanBundle', group: 'small', description: 'Clean all bundles')
    project.task('buildBundle', group: 'small', description: 'Build all bundles')
    //        project.task('smallLint', type: LintTask, group: 'small', description: 'Verify bundles')
  }

  void buildLib(Project lib) {
    def libName = lib.name
    def ext = (AndroidExtension) lib.small

    // Copy jars  build-small/intermediates/small-pre-jar/base/
    def preJarDir = small.preBaseJarDir
    if (!preJarDir.exists()) preJarDir.mkdirs()
    //  - copy package.R jar
    if (ext.jar != null) {
      def rJar = ext.jar.archivePath
      project.copy {
        from rJar
        into preJarDir
        rename { "$libName-r.jar" }
      }
      println "copy jar from ${rJar} to ${preJarDir}/${libName}-r.jar"
    }
    //  - copy dependencies jars
    ext.buildCaches.each { k, v ->
      // explodedDir: [key:value]
      // [com.android.support/appcompat-v7/25.2.0:\Users\admin\.android\build-cache\hash\output]
      File jarDir = new File(v, 'jars')
      File jarFile = new File(jarDir, 'classes.jar')
      if (!jarFile.exists()) return
      def key = k.split("/")
      def group = key[0]
      def artifact = key[1]
      def version = key[2]
      File destFile = new File(preJarDir,
          "${group}-${artifact}-${version}.jar")
      if (destFile.exists()) return

      project.copy {
        from jarFile
        into preJarDir
        rename { destFile.name }
      }
      println "copy buildCaches jar from ${jarFile} to ${preJarDir}/${destFile.name}"

      // Check if exists `jars/libs/*.jar' and copy
      File libDir = new File(jarDir, 'libs')
      libDir.listFiles().each { jar ->
        if (!jar.name.endsWith('.jar')) return

        destFile = new File(preJarDir, "${group}-${artifact}-${jar.name}")
        if (destFile.exists()) return

        project.copy {
          from jar
          into preJarDir
          rename { destFile.name }
        }
        println "copy libDir jar from ${jar} to ${preJarDir}/${destFile.name}"
      }
    }

    // Copy *.ap_
    def aapt = ext.aapt
    def preApDir = small.preApDir
    if (!preApDir.exists()) preApDir.mkdir()
    def apFile = aapt.packageOutputFile
    def preApName = "$libName-resources.ap_"
    project.copy {
      from apFile
      into preApDir
      rename { preApName }
    }
    println "copy *.ap_ from ${apFile} to ${preApDir}/${preApName}"


    // Copy R.txt
    def preIdsDir = small.preIdsDir
    if (!preIdsDir.exists()) preIdsDir.mkdir()
    def srcIdsFile = new File(aapt.textSymbolOutputDir, 'R.txt')
    if (srcIdsFile.exists()) {
      def idsFileName = "${libName}-R.txt"
      def keysFileName = 'R.keys.txt'
      def dstIdsFile = new File(preIdsDir, idsFileName)
      def keysFile = new File(preIdsDir, keysFileName)
      def addedKeys = []
      if (keysFile.exists()) {
        keysFile.eachLine { s -> addedKeys.add(SymbolParser.getResourceDeclare(s))
        }
      }
      def idsPw = new PrintWriter(dstIdsFile.newWriter(true))
      // true=append mode
      def keysPw = new PrintWriter(keysFile.newWriter(true))
      srcIdsFile.eachLine { s ->
        def key = SymbolParser.getResourceDeclare(s)
        if (addedKeys.contains(key)) return
        idsPw.println(s)
        keysPw.println(key)
      }
      idsPw.flush()
      idsPw.close()
      keysPw.flush()
      keysPw.close()
    }
    println "Copy R.txt from srcIds:${srcIdsFile.getAbsolutePath()}"

    // Backup dependencies
    if (!small.preLinkAarDir.exists()) small.preLinkAarDir.mkdirs()
    if (!small.preLinkJarDir.exists()) small.preLinkJarDir.mkdirs()
    def linkFileName = "$libName-D.txt"
    File aarLinkFile = new File(small.preLinkAarDir, linkFileName)
    File jarLinkFile = new File(small.preLinkJarDir, linkFileName)

    def allDependencies = DependenciesUtils.getAllDependencies(lib, 'compile')
    if (allDependencies.size() > 0) {
      def aarKeys = []
      if (!aarLinkFile.exists()) {
        aarLinkFile.createNewFile()
      } else {
        aarLinkFile.eachLine {
          aarKeys.add(it)
        }
      }

      def jarKeys = []
      if (!jarLinkFile.exists()) {
        jarLinkFile.createNewFile()
      } else {
        jarLinkFile.eachLine {
          jarKeys.add(it)
        }
      }

      def aarPw = new PrintWriter(aarLinkFile.newWriter(true))
      def jarPw = new PrintWriter(jarLinkFile.newWriter(true))

      // Cause the later aar(as fresco) may dependent by 'com.android.support:support-compat'
      // which would duplicate with the builtin 'appcompat' and 'support-v4' library in host.
      // Hereby we also mark 'support-compat' has compiled in host.
      // FIXME: any influence of this?
      if (lib == small.hostProject) {
        String[] builtinAars = ['com.android.support:support-compat:+',
                                'com.android.support:support-core-utils:+']
        builtinAars.each {
          if (!aarKeys.contains(it)) {
            aarPw.println it
          }
        }
      }

      allDependencies.each { d ->
        def isAar = true
        d.moduleArtifacts.each { art ->
          // Copy deep level jar dependencies
          File src = art.file
          if (art.type == 'jar') {
            isAar = false
            project.copy {
              from src
              into preJarDir
              rename { "${d.moduleGroup}-${src.name}" }
            }
          }
        }
        if (isAar) {
          if (!aarKeys.contains(d.name)) {
            aarPw.println d.name
          }
        } else {
          if (!jarKeys.contains(d.name)) {
            jarPw.println d.name
          }
        }
      }
      jarPw.flush()
      jarPw.close()
      aarPw.flush()
      aarPw.close()
    }
  }

  private void compatVendors() {
    // Check if has kotlin
    project.afterEvaluate {
      KotlinCompat.compat(project, small.kotlin)
    }
  }

  /** Hook on project build started and finished for log */
  private void injectBuildLog() {
    project.gradle.taskGraph.addTaskExecutionListener(new TaskExecutionListener() {
      @Override
      void beforeExecute(Task task) {}

      @Override
      void afterExecute(Task task, TaskState taskState) {
        if (taskState.didWork) {
          if (task.name == 'preBuild') {
            logStartBuild(task.project)
          } else if (task.name == 'assembleRelease') {
            logFinishBuild(task.project)
          }
        }
      }
    })
  }

  private void logStartBuild(Project project) {
    BaseExtension ext = project.small
    switch (ext.type) {
      case PluginType.Library:
        LibraryPlugin lp = project.plugins.findPlugin(LibraryPlugin.class)
        if (!lp.isBuildingRelease()) return
      case PluginType.Host:
        if (buildingLibIndex > 0 && buildingLibIndex <= small.libCount) {
          Log.header "building library ${buildingLibIndex++} of ${small.libCount} - " + "${project.name} (0x${ext.packageIdStr})"
        } else if (ext.type != PluginType.Host) {
          Log.header "building library ${project.name} (0x${ext.packageIdStr})"
        }
        break
      case PluginType.App:
      case PluginType.Asset:
        Log.header "building bundle ${ext.buildIndex} of ${small.bundleCount} - " + "${project.name} (0x${ext.packageIdStr})"
        break
    }
  }

  private static void logFinishBuild(Project project) {
    project.android.applicationVariants.each { variant ->
      if (variant.buildType.name != 'release') return

      variant.outputs.each { out ->
        File outFile = out.outputFile
        Log.result "${outFile.parentFile.name}/${outFile.name} " + "(${outFile.length()} bytes = ${getFileSize(outFile)})"
      }
    }
  }

  private static String getFileSize(File file) {
    long size = file.length()
    if (size <= 0) return '0'

    def units = ['B', 'KB', 'MB', 'GB', 'TB']
    int level = (int) (Math.log10(size) / Math.log10(1024))
    def formatSize = new DecimalFormat('#,##0.#').format(size / Math.pow(1024, level))
    return "$formatSize ${units[level]}"
  }
}
