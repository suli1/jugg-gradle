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

import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.internal.dsl.BuildType
import com.paem.jugg.gradle.small.tasks.CleanBundleTask
import org.gradle.api.Project

/**
 * Gradle plugin class to package 'application' or 'library' project as a .so plugin.*/
abstract class BundlePlugin extends AndroidPlugin {

  void apply(Project project) {
    super.apply(project)
  }

  @Override
  protected Class<? extends BaseExtension> getExtensionClass() {
    return BundleExtension.class
  }

  @Override
  protected BundleExtension getSmall() {
    return project.small
  }

  @Override
  protected void afterEvaluate(boolean released) {
    super.afterEvaluate(released)
    println ">> BundlePlugin.afterEvaluate"
    if (!released) return

    BuildType buildType = android.buildTypes.find { it.name == 'release' }

    Project hostProject = rootSmall.hostProject
    com.android.build.gradle.BaseExtension hostAndroid = hostProject.android
    def hostDebugBuildType = hostAndroid.buildTypes.find { it.name == 'debug' }
    def hostReleaseBuildType = hostAndroid.buildTypes.find { it.name == 'release' }

    // Copy host signing configs
    def sc = hostReleaseBuildType.signingConfig ?: hostDebugBuildType.signingConfig
    buildType.setSigningConfig(sc)

    // Enable minify if the command line defined `-Dbundle.minify=true'
    def minify = System.properties['bundle.minify']
    if (minify != null) {
      buildType.setMinifyEnabled(minify == 'true')
    }
  }

  @Override
  protected void configureReleaseVariant(BaseVariant variant) {
    super.configureReleaseVariant(variant)

    // Set output file (*.so)
//    def outputFile = getOutputFile(variant)
    variant.outputs.each { out ->
      String path = out.outputFile.absolutePath
      int indexEnd = path.lastIndexOf(File.separator)
      int indexStart = path.substring(0, indexEnd - 1).lastIndexOf(File.separator)
      out.outputFile = new File(path.substring(0, indexStart) + File.separator +
          "plugin" +
          File.separator +
          path.substring(indexEnd))

      BundleExtension ext = small
      ext.outputFile = out.outputFile

      println ">> output:${out.outputFile.getAbsolutePath()}"
    }
  }

  @Override
  protected void createTask() {
    super.createTask()

    project.task('cleanBundle', type: CleanBundleTask)
    project.task('buildBundle', dependsOn: 'assembleRelease')
  }

//  @Override
//  protected String getSmallCompileType() {
//    return 'debugCompile'
//  }

//  protected def getOutputFile(variant) {
//    def appId = variant.applicationId
//    if (appId == null) return null
//
//    RootExtension rootExt = project.rootProject.small
//    return rootExt.getBundleOutput(appId)
//  }
}