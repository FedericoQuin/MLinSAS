package util;

import smc.SMCConnector;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Properties;

public class ConfigLoader {

	private static String configFileLocation = Paths.get(System.getProperty("user.dir"), "SMCConfig.properties").toString();
	private static ConfigLoader instance = null;
	private Properties properties;

	public static ConfigLoader getInstance() {
		if (instance == null) {
			instance = new ConfigLoader();
		}
		return instance;
	}

	private ConfigLoader() {
		properties = new Properties();
		try {
			InputStream inputStream = new FileInputStream(configFileLocation);
			properties.load(inputStream);
		} catch (IOException e) {
			throw new RuntimeException("Could not load the properties file correctly.");
		}
	}

	private String getProperty(String key) {
		return properties.getProperty(key);
	}

	public int getAmountOfLearningCycles() {
		return Integer.parseInt(this.getProperty("amountOfLearningCycles"));
	}

	public int getAmountOfCycles() {
		return Integer.parseInt(this.getProperty("amountOfCycles"));
	}

	public int getDistributionGap() {
		return Integer.parseInt(this.getProperty("distributionGap"));
	}

	public SMCConnector.Mode getRunMode() {
		return SMCConnector.Mode.getMode(this.getProperty("runMode").toLowerCase());
	}

	public SMCConnector.TaskType getTaskType() {
		return SMCConnector.TaskType.getTaskType(this.getProperty("taskType").toLowerCase());
	}
}
