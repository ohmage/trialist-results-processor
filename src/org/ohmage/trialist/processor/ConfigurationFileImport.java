package org.ohmage.trialist.processor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;

/**
 * <p>
 * Attempts to read the configuration file, if it exists, and stores it. Any
 * number of the configuration options may be chosen, and all users of this
 * class should have a default in place.
 * </p>
 * 
 * @author John Jenkins
 */
public class ConfigurationFileImport {

	/**
	 * The location of the default configuration file.
	 */
	private static final String CONFIG_FILE_DEFAULT = 
		"./conf/default.properties";
		
	/**
	 * The properties merged between the defaults and the custom ones.
	 */
	private Properties defaultProperties;
			
	/**
	 * Default constructor.
	 */
	public ConfigurationFileImport() {
		setup();
	}

	/**
	 * Find the log file, if it exists, and add its properties to the system
	 * properties.
	 */
	public void setup() {
		// An empty Properties object that will be populated with the default
		// configuration.
		defaultProperties = new Properties();
		File defaultConfiguration = new File(CONFIG_FILE_DEFAULT);
		
		try {
			defaultProperties.load(new FileReader(defaultConfiguration));
		}
		// The default properties file didn't exist, which is alarming.
		catch(FileNotFoundException e) {
			throw new IllegalStateException("The default properties file is missing: " + defaultConfiguration.getAbsolutePath(), e);
		}
		// There was an error reading the default properties file.
		catch(IOException e) {
			throw new IllegalStateException("There was an error reading the default properties file: " + defaultConfiguration.getAbsolutePath(), e);
		}
	}
	
	/**
	 * Returns the custom properties defined by the external configuration 
	 * file.
	 * 
	 * @return A valid {@link Properties} object, which may or may not contain
	 * 		   the desired property.
	 */
	public Properties getProperties() {
		return defaultProperties;
	}
}
