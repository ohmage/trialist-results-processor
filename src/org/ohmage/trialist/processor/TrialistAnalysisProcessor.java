package org.ohmage.trialist.processor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import org.apache.commons.dbcp.BasicDataSource;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.joda.time.DateTime;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;


/**
 * Program that checks for completed Trialist trials and performs the following:
 * <ol>
 * <li> Formats the raw self-report into a JSON format suitable for visualization (DVU) and processing (DPU) where the respective
 * software components can use the data as-is with minimal intervention.</li>
 * <li> Persists the formatted data back into the ohmage database for later retrieval using the Stream Read API.</li>
 * <li> Passes the formatted data to an Analysis DPU for post-trial statistical processing. </li>
 * <li> Persists the analysis results back into ohmage for later retrieval using the Stream Read API.</li>
 * </ol>
 * 
 * The Stream Read API is being used instead of the DSU Read API because ohmage 2.16 implements an out-of-date beta version of the 
 * 1.0 DSU specification.
 * 
 * @author Joshua Selsky
 */
public class TrialistAnalysisProcessor {
	private static final Logger LOGGER = Logger.getLogger(TrialistAnalysisProcessor.class);
	private static final String CAMPAIGN_URN = "urn:campaign:trialist:old:3"; //"urn:campaign:trialist";
	private int numberOfTrialsProcessed = 0;
	private boolean reprocessTrials;
	private boolean reprocessAllTrials;
	private Date dateTrialEnded;
	private String campaignUrn;
	private JdbcTemplate jdbcTemplate;
	
	// Retrieve all setup and start surveys for all users for a given trialist campaign
	private String SQL_SELECT_TRIAL_SETUP_AND_START = 
		"SELECT id, user_id, survey_id, epoch_millis, survey " +
		"FROM survey_response " +
		"WHERE campaign_id = (SELECT id FROM campaign WHERE urn = ?) " +
			"AND survey_id IN ('start', 'setup') ORDER BY user_id, epoch_millis";
	
	/**
	 * Create a processor that will process the previous day's completed trials for the default Trialist campaign.
	 */
	public TrialistAnalysisProcessor() {
		reprocessTrials = false;
		reprocessAllTrials = false;
		dateTrialEnded = new Date(System.currentTimeMillis() - 8640000);
		campaignUrn = CAMPAIGN_URN;
		LOGGER.info("Processing trials for the campaign " + campaignUrn + " and trial end date " + dateTrialEnded);
	}
	
	/**
	 * Creates a processor that will process trials for the provided URN that were completed on the provided date.   
	 * 
	 * If reprocess is true, previously processed trials will have the analysis re-run. If reprocess is false, trials that have 
	 * already been processed won't be reprocessed.
	 * 
	 * Useful for processing mock trial results and re-running the processing for trials that may have already been analyzed.
	 * 
	 * @param reprocess - whether to re-run the analysis for trials that may have already had the analysis performed
	 * @param reprocessAll - whether to re-run the analysis for all completed trials
	 * @param date - the date indicating the trial end date: only trials ending on this date will be processed
	 * @param urn - the campaign URN to use 
	 */
	public TrialistAnalysisProcessor(boolean reprocess, boolean reprocessAll, Date date, String urn) {
		reprocessTrials = reprocess;
		reprocessAllTrials = reprocessAll;
		dateTrialEnded = date;
		campaignUrn = urn;
	}	
	
	/**
	 * @return how many trials were processed during the run of this program.
	 */
	private int getNumberOfTrialsProcessed() {
		return numberOfTrialsProcessed;
	}
	
	private void createjdbcTemplate(String driver, String username, String password, String jdbcUrl) {
		BasicDataSource dataSource = new BasicDataSource();
		dataSource.setDriverClassName(driver);
		dataSource.setUsername(username);
		dataSource.setPassword(password);
		dataSource.setUrl(jdbcUrl);
		jdbcTemplate = new JdbcTemplate(dataSource);
	}
		
	/**
	 * 
	 */
	public void run() {
		
		// 1. Find all finished trials. Finished trials are those where (period-days * 2 * the number of cycles) days > ( max
		// survey_response.epoch_millis - min survey_response.epoch_mills) days. 
		// 2. Make sure this trial has not already been processed.
		// 3. For each trial:
		// 3a. Convert to intermediate JSON 
		// E.g., 

		//Example Data
		//------------------
		//{
		//    "metadata": {
		//        "regimen_a":["Tylenol", "Complementary treatment: including but not limited to physical activity (exercise, stretching, yoga), mindfulness (meditation, relaxation, music therapy)"],
		//        "regimen_b":["Hydrocodone combination product (e.g., Vicodin, Norco)"],
		//        "trial_start_timestamp":"2013-11-01T12:00:00.000-08:00",
		//        "regimen_duration":7,
		//        "number_of_cycles":4,
		//        "cycle_ab_pairs":"AB,AB"
		//    }   
		//    "data": [
		//        {
		//            "timestamp":"2013-11-01T20:05:00.000-08:00",
		//            "regimen":"A",
		//            "cycle":1,
		//            "averagePainIntensity":"5",
		//            "enjoymentOfLife":5,
		//            "generalActivity":5,
		//            "fatiguePrompt":3,
		//            "drowsinessPrompt":4,
		//            "constipationPrompt":2,
		//            "cognitiveFunctionSlowThinkingPrompt":1,
		//            "painSharpness":2,
		//            "painHotness":6,
		//            "painSensitivity":6,
		//            "sleepDisturbancePrompt":3
		//        }
		//    ]
		//}
		
		// 3b. Store it. -- Create a Concordia schema and an ohmage stream.
		// 3c. Pass it to OpenCPU https://pilots.ohmage.org/ocpu/github/openmhealth/trialist.nof1.dpu/R/wrap.norm2/json
		// 3d. Store the results. -- Create a Concordia schema and an ohmage stream.
		// 3e. Mark the trial as processed. 
		
		// Now when Marc queries ohmage, he can ask for both the massaged intermediate representation (3a) and the results 
		// from 3d.
		
		// First, grab each setup survey response and each start survey response. Determine if the user's trial is over 
		// based on the trial length defined in the setup response, the time at which the user started, and the trial end date 
		// this program is configured to use.
		List<UserSurveyDate> userSetupStartList = null;
		try { 
			userSetupStartList = jdbcTemplate.query(
				SQL_SELECT_TRIAL_SETUP_AND_START, 
				new Object[] { campaignUrn }, 
				new RowMapper<UserSurveyDate>() {
					@Override
					public UserSurveyDate mapRow(ResultSet rs, int rowNum) throws SQLException {
						JSONObject survey = null;
						try {
							survey = new JSONObject(rs.getString("survey"));
						} catch (JSONException jsonException) {
							LOGGER.error("Found a survey that cannot be parsed as JSON. The primary key for the row" +
								" in survey_response is " + rs.getLong("id"));
							throw new SQLException(jsonException);
						}
						
						return new UserSurveyDate(rs.getLong("user_id"), rs.getString("survey_id"), rs.getLong("epoch_millis"), survey);
					}
				}
			);
		} catch (DataAccessException dataAccessException) {
			LOGGER.error("Something bad happened when trying to access the database.");
			throw dataAccessException;
		}
		
		LOGGER.info("Found " + (userSetupStartList == null ? " 0 " : userSetupStartList.size()) + " setup and start survey responses");
		
	    // Now determine each user's trial end date
		
		// Either process all trials that have ended or trials that have ended on the end date provided to this program
		
		long currentUserId = -1; // assume we'll never have a negative primary key
		JSONObject currentSetupSurvey = null;
		
		List<UserTrialEndDate> userTrialEndDateList = new ArrayList<UserTrialEndDate>();
		
		for(UserSurveyDate userSurveyDate : userSetupStartList) {
			if(currentUserId == -1)	{
				// Very weird edge case if the first survey is not a setup survey, but make sure anyway
				if(userSurveyDate.getSurveyId().equals("setup")) {
					currentUserId = userSurveyDate.getUserId();
					currentSetupSurvey = userSurveyDate.getSurvey();
				} 
			} else {
				if(currentUserId == userSurveyDate.getUserId()) {
					if(userSurveyDate.getSurveyId().equals("start")) {
						// Calculate the user's trial end date based on the setup config and the start date
						try {
							int cycleDuration = regimenDurationInDays(getIntValueForPromptId(currentSetupSurvey, "regimenDuration")) * 2;
							int numberOfCycles = numberOfCycles(getIntValueForPromptId(currentSetupSurvey, "numberComparisonCycles"));
							
							int totalDays = cycleDuration * numberOfCycles - 1; // subtract 1 to make the start date inclusive to the trial
							
							// FIXME: this should be retrieving the value from the start date survey
							DateTime startDateTime = new DateTime(userSurveyDate.getSurvey().getLong("time"));
							// plusDays() is actually converting startDateTime to the trial end date.
							startDateTime = startDateTime.plusDays(totalDays);
							userTrialEndDateList.add(new UserTrialEndDate(currentUserId, startDateTime));
							
						} catch (JSONException jsonException) { 
							LOGGER.error("Malformed setup survey found in the database. JSON: " + currentSetupSurvey, jsonException);
							LOGGER.info("The survey is being skipped in order to process other completed trials.");
							continue;
						}	
					}
				} else {
					if(userSurveyDate.getSurveyId().equals("setup")) {
						currentUserId = userSurveyDate.getUserId();
						currentSetupSurvey = userSurveyDate.getSurvey();
					} else { // The user has changed, but their first survey is not the setup survey, so just reset
						currentUserId = -1;
					}
				}
			}
		}
		
		// TODO - Test on opilots to verify the end date calculation and the setup / start survey processing
		for(UserTrialEndDate userTrialEndDate : userTrialEndDateList) {
			LOGGER.info(userTrialEndDate.toString());
		}
		
	}
	
	/**
	 * Map the regimen duration prompt response (the <key> element in the prompt's XML config) to the actual value in days. 
	 * Magic numbers ahoy!
	 */
	private int regimenDurationInDays(int key) {
		if(key == 0) {
			return 2;
		} else if(key == 1) {
			return 7;
		} else if (key == 2) {
			return 14;
		} else {
			throw new IllegalArgumentException("Unknown key for regimen duration: " + key);
		}
	}

	/**
	 * Map the number of cycles prompt response  (the <key> element in the prompt's XML config) to the actual number of cycles.
	 * Magic numbers ahoy!
	 */
	private int numberOfCycles(int key) {
		if(key == 0) {
			return 2;
		} else if(key == 1) {
			return 3;
		} else if (key == 2) {
			return 4;
		} else {
			throw new IllegalArgumentException("Unknown key for number of cycles: " + key);
		}
	}
	
	/**
	 * Returns the integer value for a prompt ID present in the survey object.  
	 * 
	 * @param surveyObject - An ohmage survey JSON object
	 * @return the number of days per regiment for this particular survey
	 */
	private int getIntValueForPromptId(JSONObject surveyObject, String promptId) throws JSONException {
		// Grab the responses array and then find the prompt response object that contains the key given by promptId
		JSONArray responses = (JSONArray) surveyObject.get("responses");
		int numberOfResponses = responses.length();
		for(int i = 0; i < numberOfResponses; i++) {
			if(responses.getJSONObject(i).getString("prompt_id").equals(promptId)) {
				return responses.getJSONObject(i).getInt("value");
			}
		}
		throw new JSONException("The responses array did not contain a response object for the prompt ID " + promptId);	
	}
	
	/**
	 * Main driver method to be invoked from the command-line.
	 * 
	 * If no parameters are provided, this program will process all trials completed the previous day.
	 *  
	 * The one parameter is provided, it must be a JSON object containing the keys: reprocess, reprocess-all, trial-end-date, 
	 * and campaign-urn. reprocess signals to process trials that may have already been processed - a new analysis result set will
	 * be generated; reprocess-all signals to reprocess all trials regardless of their completion date; trial-end-date indicates 
	 * that the program should process completed trials for custom end date as opposed to the previous calendar day; campaign-urn
	 * allows the URN to be customized (e.g., so only mock trials are processed). If reprocess-all is true, the values for reprocess 
	 * and trial-end-date are ignored.
	 * 
	 * Invoke with "help" as the first argument to print help text.
	 * 
	 * @param args if args[0] is present, it must be a JSON object.
	 */
	public static void main(String args[]) throws Exception {
		Properties configuredProperties = new ConfigurationFileImport().getProperties();
		// Config logging
		PropertyConfigurator.configure(configuredProperties);
		
		LOGGER.info("Starting program run at " + new Date());
				
		if(configuredProperties.getProperty("db.driver") == null) {
			LOGGER.error("The configuration file is missing the db.driver property.");
			throw new IllegalStateException("Incorrect db configuration");
		}
		
		if(configuredProperties.getProperty("db.username") == null) {
			LOGGER.error("The configuration file is missing the db.username property.");
			throw new IllegalStateException("Incorrect db configuration");
		}
		
		if(configuredProperties.getProperty("db.password") == null) {
			LOGGER.error("The configuration is missing the db.password property.");
			throw new IllegalStateException("Incorrect db configuration");
		}
		
		if(configuredProperties.getProperty("db.jdbcurl") == null) {
			LOGGER.error("The configuration is missing the db.jdbcurl property.");
			throw new IllegalStateException("Incorrect db configuration");
		}
		
		TrialistAnalysisProcessor processor = null;
		
		try {
			
			if(args.length == 0) {
				
				processor = new TrialistAnalysisProcessor();
				
			} else if(args.length == 1) {
				
				if("help".equals(args[0])) {
					help();
					return;
				}
				
				JSONObject parameters = null;
				boolean reprocess = false;
				boolean reprocessAll = false;
				String trialEndDateString = null;
				Date trialEndDate = null;
				String campaignUrn = null;
				
				try {
					parameters = new JSONObject(args[0]);
				} catch (JSONException jsonException) {
					LOGGER.error("The provided parameter is not a parseable JSON object.");
					return;
				}
				
				try {
					reprocess = parameters.getBoolean("reprocess");
				} catch (JSONException jsonException) {
					LOGGER.error("Boolean value missing for the key 'reprocess'.");
					return;
				}
				
				try {
					reprocessAll = parameters.getBoolean("reprocess-all");
				} catch (JSONException jsonException) {
					LOGGER.error("Boolean value missing for the key 'reprocess-all'.");
					return;
				}

				try {
					trialEndDateString = parameters.getString("trial-end-date");
					SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd");
					simpleDateFormat.setLenient(false);
					trialEndDate = simpleDateFormat.parse(trialEndDateString, new ParsePosition(0));
					
					if(trialEndDate == null) {
						LOGGER.error("Could not parse trial-end-date as a date of the form yyyy-mm-dd");
						return;
					}
				} catch (JSONException jsonException) {
					LOGGER.error("String value missing for the key 'trial-end-date'.");
					return;
				}
				
				try {
					campaignUrn = parameters.getString("campaign-urn");
				} catch(JSONException jsonException) {
					LOGGER.error("String value missing for the key 'campaign-urn'.");
					return;
				}
				
				processor = new TrialistAnalysisProcessor(reprocess, reprocessAll, trialEndDate, campaignUrn);
								
			} else {
				
				LOGGER.error("Could not run program because incorrect arguments were provided.");
				return;
			}
			
			processor.createjdbcTemplate(
				configuredProperties.getProperty("db.driver"), 
				configuredProperties.getProperty("db.username"),
				configuredProperties.getProperty("db.password"),
				configuredProperties.getProperty("db.jdbcurl")
			);
			
			processor.run();
		}
		
		finally {
			if(processor != null) {
				LOGGER.info("Processed " + processor.getNumberOfTrialsProcessed() + " trials.");
			} else {
				LOGGER.info("Processed 0 trials.");
			}
			
			LOGGER.info("Ending program run at " + new Date());
		}
	}
	
	private static void help() {
		System.out.println();
		System.out.println("Invoke with no arguments to process trials for the previous calendar day.");
		System.out.println("Invoke with \"help\" to show this message.");
		System.out.println("Invoke with a JSON object to customize the processing. The allowable keys in the object are:");
		System.out.println("    reprocess, a boolean that indicates whether to process trials where the analysis has been performed;");
		System.out.println("    reprocess-all, a boolean that indicates whether to reprocess all trials;");
		System.out.println("    trial-end-date, a string that is an ISO8601 date (yyyy-mm-dd) which indicates which end date to process trials for;");		
		System.out.println("    campaign-urn, a string indicating a custom campaign URN to use (e.g., for processing mock trials).");
		System.out.println();
	}
	
	/**
	 * Utility POJO for user setup and start survey chronology. 
	 */
	private static class UserSurveyDate {
		private long userId;
		private String surveyId;
		private long epochMillis;
		private JSONObject survey;
		
		public UserSurveyDate(final long pUserId, final String pSurveyId, final long pEpochMillis, final JSONObject pSurvey) {
			userId = pUserId;
			surveyId = pSurveyId;
			epochMillis = pEpochMillis;
			survey = pSurvey;
		}
		
		public long getUserId() {
			return userId;
		}

		public String getSurveyId() {
			return surveyId;
		}

		public long getEpochMillis() {
			return epochMillis;
		}

		public JSONObject getSurvey() {
			return survey;
		}
	}

	/**
	 * Utility POJO for a user's trial end date. 
	 */
	private static class UserTrialEndDate {
		private long userId;
		private DateTime trialEndDate;
		
		public UserTrialEndDate(final long pUserId, final DateTime pTrialEndDate) {
			userId = pUserId;
			trialEndDate = pTrialEndDate;
		}

		public long getUserId() {
			return userId;
		}

		public DateTime getTrialEndDate() {
			return trialEndDate;
		}

		@Override
		public String toString() {
			return "UserTrialEndDate [userId=" + userId + ", trialEndDate="
					+ trialEndDate + "]";
		}
	}
}
