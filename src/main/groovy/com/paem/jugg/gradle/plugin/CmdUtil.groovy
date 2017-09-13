package com.paem.jugg.gradle.plugin

import com.paem.jugg.gradle.AppConstant;

/**
 * Created by suli690 on 2017/8/18.*/

public class CmdUtil {

  private CmdUtil() {}

  /**
   * 同步阻塞执行命令
   */
  public static int syncExecute(String cmd) {

    int cmdReturnCode

    try {
      println "${AppConstant.TAG} \$ ${cmd}"

      Process process = cmd.execute()
      process.inputStream.eachLine {
        println "${AppConstant.TAG} - ${it}"
      }
      process.waitFor()

      cmdReturnCode = process.exitValue()
    } catch (Exception e) {
      System.err.println "${AppConstant.TAG} the cmd run error !!!"
      System.err.println "${AppConstant.TAG} ${e}"
      return -1
    }

    return cmdReturnCode
  }
}
