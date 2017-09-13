package com.paem.jugg.gradle.plugin.creator

import com.paem.jugg.gradle.plugin.PluginInfo
import groovy.json.JsonOutput
import groovy.util.slurpersupport.GPathResult
import org.gradle.api.Project

/**
 * Created by suli690 on 2017/8/16.*/

public class PluginBuildJsonCreator implements IFileCreator {

  def config
  def fileName;
  File fileDir
  def variant;

  def PluginBuildJsonCreator(Project project, def variant, def config) {
    this.config = config
    this.fileName = config.buildJsonFileName
    String mergeAssertsTaskName = variant.getVariantData().getScope().getMergeAssetsTask().name
    def mergeAssetsTask = project.tasks.getByName(mergeAssertsTaskName)
    fileDir = mergeAssetsTask.outputDir
    this.variant = variant;
  }

  @Override
  File getFileDir() {
    return fileDir
  }

  @Override
  String getFileName() {
    return fileName;
  }

  @Override
  String getFileContent() {
    def pluginInfo = new PluginInfo();

    def path = variant.outputs.processManifest.manifestOutputFile;
    println "manifestPath:${path}"
    GPathResult manifest = new XmlSlurper().parse(path);

    pluginInfo.mainClass = manifest.application.activity.find {
      it.'intent-filter'.find { filter ->
        return {
          filter.action.find {
            it.'@android:name'.text() == 'android.intent.action.MAIN'
          } && filter.category.find {
            it.'@android:name'.text() == 'android.intent.category.LAUNCHER'
          }
        }
      }
    }.'@android:name'.text();

    def metaData;

    metaData = manifest.application.'meta-data';

    if (config.pluginName == null) {
      pluginInfo.name = metaData.find {
        it -> it.'@android:name'.text() == 'plugin_name'
      }.'@android:value'.text();
    } else {
      pluginInfo.name = config.pluginName
    }

    pluginInfo.version = metaData.find {
      it -> it.'@android:name'.text() == 'plugin_version'
    }.'@android:value'.text();

    pluginInfo.minVersion = metaData.find {
      it -> it.'@android:name'.text() == 'plugin_min_version'
    }.'@android:value'.text();

    pluginInfo.otherInfo = metaData.find {
      it -> it.'@android:name'.text() == 'plugin_other_info'
    }.'@android:value'.text();

    pluginInfo.flag = metaData.find {
      it -> it.'@android:name'.text() == 'plugin_flag'
    }.'@android:value'.text();

    def jsonOutput = new JsonOutput();
    String pluginInfoJson = jsonOutput.toJson(pluginInfo);

    return pluginInfoJson;
  }
}
