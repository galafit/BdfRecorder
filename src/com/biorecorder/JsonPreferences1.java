package com.biorecorder;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;

public class JsonPreferences1 implements Preferences1 {
    private static final Log log = LogFactory.getLog(JsonPreferences1.class);
    private static final String fileName = "config.json";

    public AppConfig1 getConfig() {
        File propertyFile = getPropertyFileInProjectDir();
        if(!propertyFile.exists() || ! propertyFile.isFile()) {
            propertyFile = getPropertyFileInHomeDir();
        }

        if (propertyFile.exists() && propertyFile.isFile()) {
            JsonProperties properties = new JsonProperties(propertyFile);
            try {
                AppConfig1 appConfig = (AppConfig1) properties.getConfig(AppConfig1.class);
                return appConfig;
            } catch (IOException e) {
                log.error("Error during property file reading: " + propertyFile, e);
            }
        }
        return new AppConfig1();
    }

    @Override
    public void saveConfig(AppConfig1 appConfig) {
        File propertyFile = getPropertyFileInProjectDir();
        if(!propertyFile.exists() || ! propertyFile.isFile()) {
            try {
                propertyFile.createNewFile();
            } catch (IOException e) { // if we can´t create file in project dir, we create it in home dir
                propertyFile = getPropertyFileInHomeDir();
                if(!propertyFile.exists() || ! propertyFile.isFile()) {
                    try {
                        propertyFile.createNewFile();
                        // hide file on windows
                        String OS = System.getProperty("os.name").toLowerCase();
                        if (OS.indexOf("win") >= 0) { // windows
                            Path path = Paths.get(propertyFile.toString());
                            try {
                                Files.setAttribute(path, "dos:hidden", Boolean.TRUE, LinkOption.NOFOLLOW_LINKS);
                            } catch (IOException ex) {
                                log.error("Error during making property file hidden", ex);
                            }
                        }
                    } catch (IOException e1) {
                        String errMsg = "Error during creating property file: " + propertyFile;
                        log.error(errMsg, e1);
                    }
                }

            }
        }
        try {
            JsonProperties properties = new JsonProperties(propertyFile);
            properties.saveCongfig(appConfig);

        } catch (IOException e) {
            String errMsg = "Error during saving app config in json file: " + propertyFile;
            log.error(errMsg, e);
        }
    }

    private File getPropertyFileInProjectDir() {
        return new File(getProjectDir(), fileName);
    }


    private File getPropertyFileInHomeDir() {
        String projectDir = getProjectDir();
        String homeDir = getUserHomeDir();
        String separator = File.separator;
        // to avoid file names matches in user home dir we include "projectDir"
        // as a part of the name of property file
        projectDir = projectDir.replace(separator,"_");
        projectDir = projectDir.replace(":","");
        // start file with '.' to make file hidden on mac and unix system
        String fullFileName = "."+projectDir+ "_"+fileName;
        File propertyFile = new File(homeDir, fullFileName);
        return  propertyFile;
    }


    private String getProjectDir() {
        return System.getProperty("user.dir");
    }

    private String getUserHomeDir() {
        return System.getProperty("user.home");
    }
}