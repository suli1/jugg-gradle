package com.paem.jugg.gradle.small.transform

import com.android.build.api.transform.*
import com.android.build.api.transform.QualifiedContent.ContentType
import com.android.build.api.transform.QualifiedContent.Scope
import com.android.build.gradle.internal.pipeline.TransformManager
import com.paem.jugg.gradle.small.AppExtension
import com.paem.jugg.gradle.small.utils.AarPath
import org.apache.commons.io.FileUtils
import org.gradle.api.Project
import org.gradle.api.Task

/**
 * Created by suli690 on 2017/8/30.*/

public class StripAarTransform extends Transform {

  @Override
  String getName() {
    return "juggStripped"
  }

  @Override
  Set<ContentType> getInputTypes() {
    return TransformManager.CONTENT_CLASS
  }

  @Override
  Set<Scope> getScopes() {
    return TransformManager.SCOPE_FULL_PROJECT
  }

  @Override
  boolean isIncremental() {
    return false
  }

  @Override
  void transform(Context context, Collection<TransformInput> inputs,
      Collection<TransformInput> referencedInputs,
      TransformOutputProvider outputProvider, boolean isIncremental)
      throws IOException, TransformException, InterruptedException {
    Project project = ((Task) context).project
    AppExtension config = project.small
    inputs.each {
      // Bypass the directories
      it.directoryInputs.each {
        File dest = outputProvider.getContentLocation(it.name, it.contentTypes, it.scopes,
            Format.DIRECTORY);
        FileUtils.copyDirectory(it.file, dest)
      }

      // Filter the jars
      it.jarInputs.each {
        // Strip jars in aar or build-cache under android plugin 2.3.0+
        File src = it.file
        AarPath aarPath = new AarPath(project, src)
        for (aar in config.splitAars) {
          if (aarPath.explodedFromAar(aar)) {
            return
          }
        }

        String destName = aarPath.module.fileName
        if (src.parentFile.name == 'libs') {
          destName += '-' + src.name.substring(0, src.name.lastIndexOf('.'))
        }
        File dest = outputProvider.getContentLocation(destName, it.contentTypes, it.scopes,
            Format.JAR)
        FileUtils.copyFile(it.file, dest)
      }
    }
  }
}
