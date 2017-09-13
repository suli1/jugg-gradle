package com.paem.jugg.gradle.plugin

import com.android.build.gradle.AppExtension
import com.android.build.gradle.AppPlugin
import com.android.build.gradle.api.BaseVariantOutput
import com.paem.jugg.gradle.AppConstant
import com.paem.jugg.gradle.plugin.creator.FileCreators
import com.paem.jugg.gradle.plugin.creator.PluginBuildJsonCreator
import com.paem.jugg.gradle.plugin.debugger.PluginDebugger
import org.apache.commons.io.FileUtils
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Jugg 插件工程使用*/
class JuggPlugin implements Plugin<Project> {

  PluginExtension config;

  @Override
  void apply(Project project) {
    println "Welcome to jugg plugin!"

    project.extensions.create(AppConstant.USER_CONFIG, PluginExtension)

    if (project.plugins.hasPlugin(AppPlugin)) {
      if (config == null) {
        config = project.extensions.getByName(AppConstant.USER_CONFIG)
      }

      def startHostAppTask = null
      def forceStopHostAppTask = null
      def restartHostAppTask = null

      def android = project.extensions.getByType(AppExtension)
      android.applicationVariants.all { variant ->
        def variantData = variant.variantData;
        def scope = variantData.scope;
        def assembleTask = variant.assemble

        // task juggGenerateBuildJson
        def generateMetaJsonTaskName = scope.getTaskName(AppConstant.TASK_GENERATE_META, "")
        def generateMetaJsonTask = project.task(generateMetaJsonTaskName)
        generateMetaJsonTask.doLast {
          FileCreators.create(new PluginBuildJsonCreator(project, variant, config))
        }
        generateMetaJsonTask.group = AppConstant.TASK_GROUP;

        String mergeAssetsTaskName = scope.getMergeAssetsTask().name;
        def mergeAssetsTask = project.tasks.getByName(mergeAssetsTaskName);
        if (mergeAssetsTask != null) {
          generateMetaJsonTask.dependsOn mergeAssetsTask
          mergeAssetsTask.finalizedBy generateMetaJsonTask
        }

        // debugger
        PluginDebugger pluginDebugger = new PluginDebugger(project, config, variant);

        // task install plugin
        def installPluginTaskName = scope.getTaskName(AppConstant.TASK_INSTALL_PLUGIN, "")
        def installPluginTask = project.task(installPluginTaskName)
        installPluginTask.doLast {
          pluginDebugger.startHostApp()
          pluginDebugger.uninstall()
          pluginDebugger.forceStopHostApp()
          pluginDebugger.startHostApp()
          pluginDebugger.install()
        }
        installPluginTask.group = AppConstant.TASK_GROUP
        installPluginTask.description = "安装插件到宿主APP"

        // task uninstall plugin
        def uninstallPluginTaskName = scope.getTaskName(AppConstant.TASK_UNINSTALL_PLUGIN, "")
        def uninstallPluginTask = project.task(uninstallPluginTaskName)
        uninstallPluginTask.doLast {
          pluginDebugger.uninstall()
        }
        uninstallPluginTask.group = AppConstant.TASK_GROUP
        uninstallPluginTask.description = "从宿主APP中卸载插件"

        if (startHostAppTask == null) {
          startHostAppTask = project.task(AppConstant.TASK_START_HOST_APP)
          startHostAppTask.doLast {
            pluginDebugger.startHostApp()
          }
          startHostAppTask.group = AppConstant.TASK_GROUP
        }

        if (forceStopHostAppTask == null) {
          forceStopHostAppTask = project.task(AppConstant.TASK_FORCE_STOP_HOST_APP)
          forceStopHostAppTask.doLast {
            pluginDebugger.forceStopHostApp()
          }
          forceStopHostAppTask.group = AppConstant.TASK_GROUP
        }

        if (restartHostAppTask == null) {
          restartHostAppTask = project.task(AppConstant.TASK_RESTART_HOST_APP)
          restartHostAppTask.doLast {
            pluginDebugger.startHostApp()
          }
          restartHostAppTask.group = AppConstant.TASK_GROUP
          restartHostAppTask.dependsOn forceStopHostAppTask
        }

        if (assembleTask != null) {
          installPluginTask.dependsOn assembleTask
        }

        // 将内部插件拷贝到宿主的assets目录下
        if (config.targetHost != null && config.isInternal && variant.outputs.size() > 0) {
          BaseVariantOutput output = variant.outputs.get(0)

          def copyApkTaskName = scope.getTaskName(AppConstant.TASK_COPY, "Apk")
          def copyApkTask = project.task(copyApkTaskName)
          copyApkTask.doLast {
            println "${AppConstant.TAG} start copy apk:${output.outputFile.toPath()}"
            if (config.targetHost.startsWith("..")) {
              config.targetHost = project.projectDir.path + File.separator + config.targetHost
            }

            // FIXME 目前使用的是默认位置的assets目录
            if (config.rename == null) {
              config.rename = output.outputFile.name
            }

            def targetPath = new File(config.targetHost + File.separator +
                'src' +
                File.separator +
                'main' +
                File.separator +
                'assets' +
                File.separator +
                config.rename)
            FileUtils.copyFile(output.outputFile, targetPath)

            println "${AppConstant.TAG} copy ${output.outputFile.name} to ${targetPath}${File.separator}${config.rename}"
          }
          copyApkTask.group = AppConstant.TASK_GROUP

          if (assembleTask != null) {
            copyApkTask.dependsOn assembleTask
            assembleTask.finalizedBy copyApkTask
          }
        }
      }
    }
  }
}