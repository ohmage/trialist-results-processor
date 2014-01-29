package org.ohmage.trialist.processor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import org.apache.commons.dbcp.BasicDataSource;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.ISODateTimeFormat;
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
	
	// The default campaign to process
	private static final String CAMPAIGN_URN = "urn:campaign:trialist:old:3"; //"urn:campaign:trialist";
	
	// Parameters to main to customize processing
	private boolean reprocessTrials;
	private boolean reprocessAllTrials;
	private DateTime dateTrialEnded;
	private DateTime yesterday;
	private String campaignUrn;
	
	// Program execution info
	private int numberOfTrialsProcessed = 0;
	
	// Database connectivity
	private JdbcTemplate jdbcTemplate;
	
	// Retrieve all setup and start surveys for all users for a given trialist campaign
	private String SQL_SELECT_TRIAL_SETUP_AND_START = 
		"SELECT id, user_id, survey_id, survey " +
		"FROM survey_response " +
		"WHERE campaign_id = (SELECT id FROM campaign WHERE urn = ?) " +
			"AND survey_id IN ('start', 'setup') ORDER BY user_id, epoch_millis";
	
	// Lazily retrieve all of the processed trials. In practice, this should return a max of fewer than 500 rows 
	private String SQL_SELECT_PROCESSED_TRIALS = "SELECT DISTINCT user_id, setup_survey_response_id from trialist_processed_trial";
	
	/**
	 * Create a processor that will process the previous day's completed trials for the default Trialist campaign.
	 */
	public TrialistAnalysisProcessor() {
		reprocessTrials = false;
		reprocessAllTrials = false;
		dateTrialEnded = new DateTime(System.currentTimeMillis() - 8640000).withZone(DateTimeZone.forID("UTC")).withTime(0, 0, 0, 0);
		yesterday = new DateTime(System.currentTimeMillis() - 8640000).withZone(DateTimeZone.forID("UTC")).withTime(0, 0, 0, 0);
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
	public TrialistAnalysisProcessor(boolean reprocess, boolean reprocessAll, DateTime date, String urn) {
		reprocessTrials = reprocess;
		reprocessAllTrials = reprocessAll;
		dateTrialEnded = date;
		yesterday = new DateTime(System.currentTimeMillis() - 8640000).withZone(DateTimeZone.forID("UTC")).withTime(0, 0, 0, 0);
		campaignUrn = urn;
		
		LOGGER.info("Processing trials for campaign " + campaignUrn + " and for end date " + dateTrialEnded 
				+ " reprocessTrials is " + reprocessTrials + " and reprocessAllTrials is " + reprocessAllTrials);
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
		//        "trial_start_date":"2013-11-01",
		//        "trial_end_date":"2013-11-01",
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
		
		// Find all processed trials to handling filtering in case case trial reprocessing is not desired
		List<ProcessedTrial> processedTrials = null;

		try {
			processedTrials = jdbcTemplate.query(
				SQL_SELECT_PROCESSED_TRIALS, 
				new RowMapper<ProcessedTrial>() {
					@Override
					public ProcessedTrial mapRow(ResultSet rs, int rowNum) throws SQLException {
						return new ProcessedTrial(rs.getLong("user_id"), rs.getLong("setup_survey_response_id"));
					}
				}
			);
			
		} catch (DataAccessException dataAccessException) {
			LOGGER.error("An error occurred when accessing the database.");
			throw dataAccessException;
		}
		
		if(processedTrials == null) {
			processedTrials = Collections.<ProcessedTrial>emptyList();
		}
		
		// Grab each setup survey response and each start survey response. Determine if the user's trial is over 
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
						
						return new UserSurveyDate(rs.getLong("id"), rs.getLong("user_id"), rs.getString("survey_id"), survey);
					}
				}
			);
		} catch (DataAccessException dataAccessException) {
			LOGGER.error("An error occurred when accessing the database.");
			throw dataAccessException;
		}
		
		if(userSetupStartList == null) {
			userSetupStartList = Collections.<UserSurveyDate>emptyList();
		}
		
		LOGGER.info("Found " + userSetupStartList.size() + " setup and start survey responses");
		
	    // Now determine each user's trial end date
		
		long currentUserId = -1;                 // assume we'll never have a negative primary key
		long currentSetupSurveyPrimaryKey = -1;  // ditto
		JSONObject currentSetupSurvey = null;
		
		List<UserTrial> userTrials = new ArrayList<UserTrial>();
		
		for(UserSurveyDate userSurveyDate : userSetupStartList) {
			if(currentUserId == -1)	{
				// Very weird edge case if the first survey is not a setup survey, but make sure anyway
				if(userSurveyDate.getSurveyId().equals("setup")) {
					currentUserId = userSurveyDate.getUserId();
					currentSetupSurvey = userSurveyDate.getSurvey();
					currentSetupSurveyPrimaryKey = userSurveyDate.getSurveyPrimaryKey();
				} 
			} else {
				if(currentUserId == userSurveyDate.getUserId()) {
					if(userSurveyDate.getSurveyId().equals("start")) {
						// Calculate the user's trial end date based on the setup config and the start date
						try {
							// Multiply by 2 because each regimen duration is half a cycle
							int cycleDuration = regimenDurationInDays(getIntValueForPromptId(currentSetupSurvey, "regimenDuration")) * 2;
							int numberOfCycles = numberOfCycles(getIntValueForPromptId(currentSetupSurvey, "numberComparisonCycles"));
							
							// NOTE: JodaTime requires the long version of the timezone ID. It will accept America/Los_Angeles, but 
							// reject Etc/GMT-8 or PST. The latter formats will cause an IllegalArgumentException.
							// Trialist-MWF (phone app) uses a JavaScript library to generate long timezone IDs and ohmage
							// server uses JodaTime to validate timezone input for survey responses, so a malformed timezone 
							// should never occur
							DateTimeZone startDateTimeZone  = null;
							
							try {
								
								startDateTimeZone = DateTimeZone.forID(userSurveyDate.getSurvey().getString("timezone"));
								
							} catch (IllegalArgumentException unknownTimeZone) {
								// This means that somehow the server app persisted a timezone that Joda cannot parse. 
								// Just Skip the response and log the incorrectly formatted data.
								LOGGER.warn("Found a start survey with a timezone that JodaTime cannot parse. The value is: " 
										+ userSurveyDate.getSurvey().getString("timezone"));
								continue;
							}

							// The user's timezone needs to be provided as the second parameter otherwise JodaTime will default to
							// the timezone of the machine this program is running on. After the DateTime is created, the time 
							// and timezone fields are normalized because only the date portion of the DateTime will be needed
							// for later processing.
							DateTime startDateTime = new DateTime(
								getStringValueForPromptId(userSurveyDate.getSurvey(), "startPrompt"), startDateTimeZone)
									.withZone(DateTimeZone.forID("UTC"))
									.withTime(0, 0, 0, 0);
							
							// The phone app saves the start date as the current day if the current local time is before
							// 8:00pm and the next day if it is after 8:00pm, so no need to handle the time here 
							
							// Subtract 1 to make the start date inclusive to the trial end date calculation
							int totalDays = cycleDuration * numberOfCycles - 1; 
							
							// Calculate the end date, strip out the time, and set the tz to UTC because this value is only used in
							// an equals() comparison with another yyyy-mm-dd UTC date.
							DateTime endDateTime = startDateTime.plusDays(totalDays)
								.withZone(DateTimeZone.forID("UTC"))
								.withTime(0, 0, 0, 0);
							
							userTrials.add(new UserTrial(currentUserId, startDateTime, endDateTime, currentSetupSurvey, currentSetupSurveyPrimaryKey)); 
							
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
		
		for(UserTrial userTrial : userTrials) {
			LOGGER.info(userTrial.toString());
		}
		
		// Filter out all trials that should not be processed
		
		List<UserTrial> trialsToProcess = filterTrialsForReprocessing(filterTrialsByDate(userTrials), processedTrials);
 		
		LOGGER.info(trialsToProcess.size() + " trials will be processed");
		
		for(UserTrial userTrial : trialsToProcess) {
			LOGGER.info(userTrial.toString());
		}
		
		// Create and persist the intermediate data format.
		
		// Pass to DPU.
		
		// Persist DPU results.
		
		// Mark trial as processed.
		
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
	 * Returns the String value for a prompt ID present in the survey object.   
	 */
	private String getStringValueForPromptId(JSONObject surveyObject, String promptId) throws JSONException {
		// Grab the responses array and then find the prompt response object that contains the key given by promptId
		JSONArray responses = (JSONArray) surveyObject.get("responses");
		int numberOfResponses = responses.length();
		for(int i = 0; i < numberOfResponses; i++) {
			if(responses.getJSONObject(i).getString("prompt_id").equals(promptId)) {
				return responses.getJSONObject(i).getString("value");
			}
		}
		throw new JSONException("The responses array did not contain a response object for the prompt ID " + promptId);	
	}
	
	
	/**
	 * Returns a list of user trials that should be processed based the trial end date parameter and whether reprocessAllTrials 
	 * is true.
	 */
	private List<UserTrial> filterTrialsByDate(List<UserTrial> trialsToCheck) {
		if(trialsToCheck == null || trialsToCheck.isEmpty()) {
			return Collections.<UserTrial>emptyList();
		}
		
		List<UserTrial> trialsToProcess = new ArrayList<UserTrial>();
		
		for(UserTrial userTrial : trialsToCheck) {
			// Don't process trials that are not finished yet
			if(userTrial.getTrialEndDate().compareTo(yesterday) <= 0) {
				 
				if(reprocessAllTrials) {
					// Any finished trial will be processed
					trialsToProcess.add(userTrial);
					
				} else {
					// Otherwise only trials ending on end date parameter to this program will be processed
					if(userTrial.getTrialEndDate().equals(dateTrialEnded)) {
						trialsToProcess.add(userTrial);
					}
				}	
			}
		}
		
		return trialsToProcess;
	}
	
	/**
	 * If reprocessTrials is false, this method filters out any trial that has already been processed. 
	 */
	private List<UserTrial> filterTrialsForReprocessing(List<UserTrial> trialsToCheck, List<ProcessedTrial> processedTrials) { 
		if(! reprocessTrials) {
			// Use an iterator because the list might be modified as it is traversed
			Iterator<UserTrial> iterator = trialsToCheck.iterator();
			while(iterator.hasNext()) {
				UserTrial userTrial = iterator.next();
				if(processedTrials.contains(new ProcessedTrial(userTrial.getUserId(), userTrial.getSetupSurveyPrimaryKey()))) {
					iterator.remove();
				}
			}
			return trialsToCheck;
			
		} else {
			return trialsToCheck;
		}
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
				DateTime trialEndDate = null;
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
					
					// This will throw an IllegalArgumentException if the date string is not parseable
					trialEndDate = ISODateTimeFormat.yearMonthDay().parseDateTime(trialEndDateString)
						.withZone(DateTimeZone.forID("UTC"))
						.withTime(0, 0, 0, 0);
					
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
	 * Domain object for participant setup and start surveys. 
	 */
	private static class UserSurveyDate {
		private long surveyPrimaryKey;
		private long userId;
		private String surveyId;
		private JSONObject survey;
		
		public UserSurveyDate(final long pSurveyPrimaryKey, final long pUserId, final String pSurveyId, final JSONObject pSurvey) {
			surveyPrimaryKey = pSurveyPrimaryKey;
			userId = pUserId;
			surveyId = pSurveyId;
			survey = pSurvey;
		}
		
		public long getUserId() {
			return userId;
		}

		public String getSurveyId() {
			return surveyId;
		}
		
		public long getSurveyPrimaryKey() {
			return surveyPrimaryKey;
		}

		public JSONObject getSurvey() {
			return survey;
		}
	}

	/**
	 * Domain object for a user's trial data. 
	 */
	private static class UserTrial {
		private long userId;
		private DateTime trialStartDate;
		private DateTime trialEndDate;
		private JSONObject setupSurvey;
		private long setupSurveyPrimaryKey;
		
		public UserTrial(final long pUserId, final DateTime pTrialStartDate, final DateTime pTrialEndDate, JSONObject pSetupSurvey, final long pSetupSurveyPrimaryKey) {
			userId = pUserId;
			trialStartDate = pTrialStartDate;
			trialEndDate = pTrialEndDate;
			setupSurvey = pSetupSurvey;
			setupSurveyPrimaryKey = pSetupSurveyPrimaryKey;
		}

		public long getUserId() {
			return userId;
		}

		public DateTime getTrialStartDate() {
			return trialStartDate;
		}

		public DateTime getTrialEndDate() {
			return trialEndDate;
		}

		public JSONObject getSetupSurvey() {
			return setupSurvey;
		}

		public long getSetupSurveyPrimaryKey() {
			return setupSurveyPrimaryKey;
		}
		
		// Omitted setupSurveyPrimary key for less verbose debugging
		@Override
		public String toString() {
			return "UserTrial [userId=" + userId + ", trialStartDate="
					+ trialStartDate + ", trialEndDate=" + trialEndDate
					/*+ ", setupSurvey=" + setupSurvey*/ + "]";
		}	
	}
	
	/**
	 * Domain object representation of a row in the trialist_processed_trial table.
	 */
	private static class ProcessedTrial {
		private long userId;
		private long surveyPrimaryKey;
		
		public ProcessedTrial(final long pUserId, final long pSurveyPrimaryKey) {
			userId = pUserId;
			surveyPrimaryKey = pSurveyPrimaryKey;	
		}

		public long getUserId() {
			return userId;
		}

		public long getSurveyPrimaryKey() {
			return surveyPrimaryKey;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result
					+ (int) (surveyPrimaryKey ^ (surveyPrimaryKey >>> 32));
			result = prime * result + (int) (userId ^ (userId >>> 32));
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			ProcessedTrial other = (ProcessedTrial) obj;
			if (surveyPrimaryKey != other.surveyPrimaryKey)
				return false;
			if (userId != other.userId)
				return false;
			return true;
		}
	}
}
