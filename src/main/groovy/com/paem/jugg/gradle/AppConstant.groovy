package com.paem.jugg.gradle;

/**
 * Created by suli690 on 2017/8/16.*/

public class AppConstant {

  def static final TAG = ">>paic-jugg"

  def static final USER_CONFIG = "juggPluginConfig"

  def static final TASK_GROUP = "jugg-plugin"

  def static final TASK_PREFIX = "jugg"

  /** 用户Task:Generate任务*/
  def static final TASK_GENERATE_META = TASK_PREFIX + "GenerateBuildJson"

  /** 拷贝内置插件到宿主的asset目录*/
  def static final TASK_COPY = TASK_PREFIX + "Copy"

  /** 启动宿主APP */
  def static final TASK_START_HOST_APP = TASK_PREFIX + "StartHostApp"

  /** 强制停止宿主APP */
  def static final TASK_FORCE_STOP_HOST_APP = TASK_PREFIX + "ForceStopHostApp"

  /** 重启宿主APP */
  def static final TASK_RESTART_HOST_APP = TASK_PREFIX + "RestartHostApp"

  /** 安装插件 */
  def static final TASK_INSTALL_PLUGIN = TASK_PREFIX + "InstallPlugin"

  /** 卸载插件 */
  def static final TASK_UNINSTALL_PLUGIN = TASK_PREFIX + "UninstallPlugin"
}
