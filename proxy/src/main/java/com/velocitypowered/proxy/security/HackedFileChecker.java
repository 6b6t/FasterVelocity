package com.velocitypowered.proxy.security;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.File;

public final class HackedFileChecker {
    private static final Logger logger = LogManager.getLogger(HackedFileChecker.class);
    
    private HackedFileChecker() {
        // Prevent instantiation
    }
    
    public static void checkForHackedFile() {
        File hackedFile = new File("hacked");
        if (hackedFile.exists()) {
            logger.error("SECURITY ALERT: 'hacked' file detected - server was previously compromised");
            logger.error("Shutting down for security reasons. Remove the 'hacked' file only after security audit.");
            System.exit(1);
        }
    }
}
