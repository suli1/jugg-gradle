package com.paem.jugg.gradle.plugin.creator;

/**
 * Created by suli690 on 2017/8/16.*/

public interface IFileCreator {
  /**
   * 要生成的文件的目录*/
  File getFileDir();

  /**
   * 要生成的文件名称*/
  String getFileName();

  /**
   * 要生成的文件的内容*/
  String getFileContent();

}
