package com.paem.jugg.gradle.plugin.creator

import com.paem.jugg.gradle.AppConstant;

/**
 * Created by suli690 on 2017/8/16.*/

public class FileCreators {

  static def create(IFileCreator creator) {
    if (creator == null) {
      return
    }

    def dir = creator.getFileDir()
    if (!dir.exists()) {
      println "${AppConstant.TAG} mkdirs ${dir.getAbsoluteFile()} : ${dir.mkdir()}"
    }

    def targetFile = new File(dir, creator.getFileName())

    def content = creator.getFileContent()
    if (content == null) {
      return
    }

    targetFile.write(content, "UTF-8");
    println "${AppConstant.TAG} write ${targetFile.getAbsoluteFile()}"
  }
}
