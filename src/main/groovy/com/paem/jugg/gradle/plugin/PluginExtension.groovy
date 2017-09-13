package com.paem.jugg.gradle.plugin;

/**
 * Created by suli690 on 2017/8/16.
 * jugg插件工程的gradle配置
 */

public class PluginExtension {

  String hostApplicationId

  String hostAppLauncherActivity

  String pluginName;

  String phoneStorageDir = "/sdcard/"

  String buildJsonFileName = "paplugin.meta" // paplugin.meta

  String targetHost           // 宿主的工程目录

  boolean isInternal = false   // 是否是内置插件

  String rename           // 如果设置了输出名称，则对输出结果修改为该名称
}
