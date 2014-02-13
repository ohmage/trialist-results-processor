package org.ohmage.trialist.processor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import org.apache.commons.dbcp.BasicDataSource;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Days;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.DateTimeFormatterBuilder;
import org.joda.time.format.ISODateTimeFormat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
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
	
	// The observer stream metadata for storing normalized self-report and final analysis data
	private static final String OBSERVER_ID = "io.omh.trialist";
	private static final String OBSERVER_VERSION = "2013013000";
	private static final String DATA_STREAM_ID = "data";
	private static final String ANALYSIS_RESULTS_STREAM_ID = "results";
	private static final String DATA_STREAM_VERSION = "2013013000";
	private static final String ANALYSIS_RESULTS_STREAM_VERSION = "2013013000";
	
	// OpenCPU HTTP connectivity
	private static final String OCPU_URL = "https://pilots.ohmage.org/ocpu/github/jservadio/TrialistNof1/R/wrap/json";
	
	// Processing customization
	private boolean alsoReprocessTrials;
	private boolean alsoReprocessAllTrials;
	private DateTime dateTrialEnded;
	private DateTime yesterday;
	private String campaignUrn;
	
	// Program execution info
	private int numberOfTrialsProcessed = 0;
	
	// Database connectivity
	private JdbcTemplate jdbcTemplate;
	
	// Retrieve all setup and start surveys for all users for a given trialist campaign
	private static final String SQL_SELECT_TRIAL_SETUP_AND_START = 
		"SELECT uuid, user_id, survey_id, survey " +
		"FROM survey_response " +
		"WHERE campaign_id = (SELECT id FROM campaign WHERE urn = ?) " +
			"AND survey_id IN ('start', 'setup') ORDER BY user_id, epoch_millis";
	
	// Find any trials that have analysis results
	private static final String SQL_SELECT_PROCESSED_TRIALS = 
		"SELECT observer_stream_data.user_id, data FROM observer_stream_data " +
		"LEFT JOIN observer_stream_link ON observer_stream_link_id = observer_stream_link.id " +
		"LEFT JOIN observer ON observer_stream_link.observer_id = observer.id " +
		"LEFT JOIN observer_stream ON observer_stream_link.observer_stream_id = observer_stream.id " +
		"WHERE observer.observer_id = '" + OBSERVER_ID + "'" + 
		" AND observer.version = '" + OBSERVER_VERSION + "'" +
		" AND observer_stream.stream_id = '" + ANALYSIS_RESULTS_STREAM_ID + "'" +  
		" AND observer_stream.version = '" + ANALYSIS_RESULTS_STREAM_VERSION + "'";

	// Find normalized trial results for a given user 
	private static final String SQL_SELECT_EXISTS_TRIALIST_STREAM_DATA_POINTS 
		= "SELECT EXISTS (SELECT data FROM observer_stream_data " +
			"LEFT JOIN observer_stream_link ON observer_stream_link_id = observer_stream_link.id " +
			"LEFT JOIN observer ON observer_stream_link.observer_id = observer.id " +
			"LEFT JOIN observer_stream ON observer_stream_link.observer_stream_id = observer_stream.id " +
			"WHERE observer.observer_id = '" + OBSERVER_ID + "'" + 
			" AND observer.version = '" + OBSERVER_VERSION + "'" +
			" AND observer_stream.stream_id = '" + DATA_STREAM_ID + "'" +  
			" AND observer_stream.version = '" + DATA_STREAM_VERSION + "'" + 
			" AND observer_stream_data.user_id = ?)";

	
	// Find normalized trial results for a given user 
	private static final String SQL_SELECT_TRIALIST_STREAM_DATA_POINTS 
		= "SELECT data FROM observer_stream_data " +
			"LEFT JOIN observer_stream_link ON observer_stream_link_id = observer_stream_link.id " +
			"LEFT JOIN observer ON observer_stream_link.observer_id = observer.id " +
			"LEFT JOIN observer_stream ON observer_stream_link.observer_stream_id = observer_stream.id " +
			"WHERE observer.observer_id = '" + OBSERVER_ID + "'" + 
			" AND observer.version = '" + OBSERVER_VERSION + "'" +
			" AND observer_stream.stream_id = '" + DATA_STREAM_ID + "'" +  
			" AND observer_stream.version = '" + DATA_STREAM_VERSION + "'" + 
			" AND observer_stream_data.user_id = ?";
	
	// Get all of the Trialist main surveys for a given user. "main" is the name given to the daily self-report survey in Trialist
	private static final String SQL_SELECT_MAIN_SURVEY_PROMPT_RESPONSES_FOR_USER =
		"SELECT sr.id, sr.epoch_millis, sr.phone_timezone, pr.prompt_id, pr.response " +
		"FROM prompt_response pr, survey_response sr " +
		"WHERE pr.survey_response_id = sr.id " +
			"AND sr.survey_id = 'main' " +
			"AND sr.campaign_id = (SELECT id FROM campaign where urn = '" + CAMPAIGN_URN + "') " +
			"AND DATE(FROM_UNIXTIME(sr.epoch_millis / 1000)) BETWEEN ? AND ? " +
			"AND sr.user_id = ? " +
			"ORDER BY user_id, epoch_millis asc";
	
	// Save the normalized trial data points
	private static final String SQL_INSERT_TRIAL_DATA_POINTS = 
		"INSERT INTO observer_stream_data " +
		"(user_id, observer_stream_link_id, data) VALUES " +
			"(?, " +
			"(SELECT osl.id FROM observer_stream_link osl, observer_stream os, observer o WHERE o.observer_id = '" + OBSERVER_ID +
				"' AND o.version = '" + OBSERVER_VERSION + "' AND os.stream_id = '" + DATA_STREAM_ID +
				"' AND os.version = '" + DATA_STREAM_VERSION + "' AND osl.observer_id = o.id AND osl.observer_stream_id = os.id), " +
			"?)";

	// Save the trial analysis results
	private static final String SQL_INSERT_TRIAL_ANALYSIS_RESULTS = 
		"INSERT INTO observer_stream_data " +
		"(user_id, observer_stream_link_id, data) VALUES " +
			"(?, " +
			"(SELECT osl.id FROM observer_stream_link osl, observer_stream os, observer o WHERE o.observer_id = '" + OBSERVER_ID +
				"' AND o.version = '" + OBSERVER_VERSION + "' AND os.stream_id = '" + ANALYSIS_RESULTS_STREAM_ID + 
				"' AND os.version = '" + ANALYSIS_RESULTS_STREAM_VERSION + "' AND osl.observer_id = o.id " +
				"AND osl.observer_stream_id = os.id), " +
			"?)";
	
	/**
	 * Create a processor that will process the previous day's completed trials for the default Trialist campaign.
	 */
	public TrialistAnalysisProcessor() {
		alsoReprocessTrials = false;
		alsoReprocessAllTrials = false;
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
		alsoReprocessTrials = reprocess;
		alsoReprocessAllTrials = reprocessAll;
		dateTrialEnded = date;
		yesterday = new DateTime(System.currentTimeMillis() - 8640000).withZone(DateTimeZone.forID("UTC")).withTime(0, 0, 0, 0);
		campaignUrn = urn;
		
		LOGGER.info("Processing trials for campaign " + campaignUrn + " and for end date " + dateTrialEnded 
				+ " reprocessTrials is " + alsoReprocessTrials + " and reprocessAllTrials is " + alsoReprocessAllTrials);
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
	public void run() throws IOException, JSONException {
		List<ProcessedTrial> processedTrials = null;
		
		// Date formatter to strip off times and timezones from trial start and end dates
		DateTimeFormatterBuilder builder = new DateTimeFormatterBuilder();
		builder.append(ISODateTimeFormat.yearMonthDay().getPrinter(), ISODateTimeFormat.yearMonthDay().getParser());
		DateTimeFormatter yearMonthDayFormatter = builder.toFormatter().withZoneUTC();
		
		// Date formatter to print datetimes returned from the db
		builder.clear();
		builder.append(ISODateTimeFormat.dateTime().getPrinter(), ISODateTimeFormat.dateTime().getParser());
		DateTimeFormatter dateTimeFormatter = builder.toFormatter(); // the timezone is dependent on the user's data
		
		// Find all processed trials to handling filtering in case case trial reprocessing is not desired
		try {
			processedTrials = jdbcTemplate.query(
				SQL_SELECT_PROCESSED_TRIALS, 
				new ResultSetExtractor<List<ProcessedTrial>>() {
					@Override
					public List<ProcessedTrial> extractData(ResultSet rs) throws SQLException {
						try {
							// Filter out duplicates because a trial can be analyzed multiple times and for the purposes of this 
							// program it only matters if it was processed once
							
							List<ProcessedTrial> processedTrials = new ArrayList<ProcessedTrial>();
							
							while(rs.next()) {
								ProcessedTrial pt = new ProcessedTrial(
									rs.getLong("user_id"), 
									new JSONObject(rs.getString("data")).getString("setup_survey_id")); 
								
								if(! processedTrials.contains(pt)) {
									processedTrials.add(pt);
								}
							}
							
							return processedTrials;
							
						} catch(JSONException jsonException) {
							
							throw new SQLException("Could not retrieve value for setup_survey_id in trial analysis results.", jsonException);
						}
					}
				}
			);
			
		} catch (DataAccessException dataAccessException) {
			LOGGER.error("An error occurred when accessing the database to retrieve already processed trials.");
			throw dataAccessException;
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
						
						return new UserSurveyDate(rs.getString("uuid"), rs.getLong("user_id"), rs.getString("survey_id"), survey);
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
		String currentSetupSurveyUuid = null;  
		JSONObject currentSetupSurvey = null;
		
		List<UserTrial> userTrials = new ArrayList<UserTrial>();
		
		for(UserSurveyDate userSurveyDate : userSetupStartList) {
			if(currentUserId == -1)	{
				// Very weird edge case if the first survey is not a setup survey, but make sure anyway
				if(userSurveyDate.getSurveyId().equals("setup")) {
					currentUserId = userSurveyDate.getUserId();
					currentSetupSurvey = userSurveyDate.getSurvey();
					currentSetupSurveyUuid = userSurveyDate.getSurveyUuid();
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
								// Just skip the response and log the incorrectly formatted data.
								LOGGER.warn("Found a start survey with a timezone that JodaTime cannot parse. The value is: " 
										+ userSurveyDate.getSurvey().getString("timezone"));
								throw unknownTimeZone;
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
							
							userTrials.add(new UserTrial(currentUserId, startDateTime, endDateTime, currentSetupSurvey, currentSetupSurveyUuid)); 
							
						} catch (JSONException jsonException) { 
							LOGGER.error("Malformed setup survey found in the database. JSON: " + currentSetupSurvey, jsonException);
							throw jsonException;
						}	
					}
				} else {
					if(userSurveyDate.getSurveyId().equals("setup")) {
						currentUserId = userSurveyDate.getUserId();
						currentSetupSurvey = userSurveyDate.getSurvey();
						currentSetupSurveyUuid = userSurveyDate.getSurveyUuid();
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
 		
		LOGGER.info(trialsToProcess.size() + " trial(s) will be processed");
		
//		for(UserTrial userTrial : trialsToProcess) {
//			LOGGER.info(userTrial.toString());
//		}
		
		// Find the normalized data for any previously processed trial in the list
		// The data will potentially exist if trials are being reprocessed or if this program has already been run for a given
		// trial end date.
			
		for(UserTrial userTrial : trialsToProcess) {
			try {
				
				if(jdbcTemplate.queryForObject(SQL_SELECT_EXISTS_TRIALIST_STREAM_DATA_POINTS, Boolean.class, userTrial.getUserId())) {
				
					userTrial.setNormalizedData(
						jdbcTemplate.queryForObject(
							SQL_SELECT_TRIALIST_STREAM_DATA_POINTS, 
							new Object[] { userTrial.getUserId() }, 
							new RowMapper<JSONObject>() {
								@Override
								public JSONObject mapRow(ResultSet rs, int rowNum) throws SQLException {
									try {
										return new JSONObject(rs.getString("data"));
									} catch (JSONException jsonException) {
										LOGGER.error("Found stream data that cannot be parsed as JSON. The value returned from " +
											"observer_stream_data is " + rs.getString("data"));
										throw new SQLException(jsonException);
									}
								}
							}
						)
					);
				}
				
			} catch (DataAccessException dataAccessException) {
				LOGGER.error("An error occurred when accessing the database.");
				throw dataAccessException;
			}
		}
		
		// Create the normalized data stream for each trial 
		for(UserTrial userTrial : trialsToProcess) {
			if(userTrial.getNormalizedData() == null) { // If this trial has not already been processed, create the intermediate 
				                                        // representation of the data and store it
				
				SurveyReponseRowCallbackHandler surveyResponseHandler = new SurveyReponseRowCallbackHandler();
				
				try { 
					
					LOGGER.info("Params to retrieving main surveys. " +
						"Start date " + yearMonthDayFormatter.print(userTrial.getTrialStartDate()) +
						", end date " + yearMonthDayFormatter.print(userTrial.getTrialEndDate()) + 
						",  user ID " + userTrial.getUserId());
					
					jdbcTemplate.query(
						SQL_SELECT_MAIN_SURVEY_PROMPT_RESPONSES_FOR_USER, 
						new Object[] { yearMonthDayFormatter.print(userTrial.getTrialStartDate()), 
									   yearMonthDayFormatter.print(userTrial.getTrialEndDate()), 
									   userTrial.getUserId()  },
						surveyResponseHandler
					);
				} catch (DataAccessException dataAccessException) {
					LOGGER.error("An error occurred when accessing the database.");
					throw dataAccessException;
				}
				
				LOGGER.info("Found " + surveyResponseHandler.getSurveyResponses().size() 
					+ " survey responses for the main survey for user " + userTrial.getUserId());
				
				// Now convert the list of responses into the normalized format

				JSONObject root = new JSONObject();
				JSONObject metadata = new JSONObject();
				JSONArray dataArray = new JSONArray();
				
				int regimenDuration = -1;
				int numberOfCycles = -1;
				
				// Metadata Section
				
				try {
					metadata.put("regimen_a", regimenArray(userTrial.getSetupSurvey(), "regimenA", campaignUrn));
					metadata.put("regimen_b", regimenArray(userTrial.getSetupSurvey(), "regimenB", campaignUrn));
					metadata.put("trial_start_date", yearMonthDayFormatter.print(userTrial.getTrialStartDate()));
					metadata.put("trial_end_date", yearMonthDayFormatter.print(userTrial.getTrialEndDate()));
					regimenDuration = regimenDurationInDays(getIntValueForPromptId(userTrial.getSetupSurvey(), "regimenDuration"));
					metadata.put("regimen_duration", regimenDuration);
					numberOfCycles = numberOfCycles(getIntValueForPromptId(userTrial.getSetupSurvey(), "numberComparisonCycles"));
					metadata.put("number_of_cycles", numberOfCycles);
					String randomABPairs = getStringValueForPromptId(userTrial.getSetupSurvey(), "randomAsText");
					metadata.put("cycle_ab_pairs", randomABPairs);
					metadata.put("cognitiveFunctionPromptKey", getStringValueForPromptId(userTrial.getSetupSurvey(), "cognitiveFunction"));
					
					root.put("metadata", metadata);
					
				} catch (JSONException jsonException) {
					
					LOGGER.error("Could not create metadata object for the analysis data set because of invalid JSON " +
						"or a missing key in the setup survey. The survey UUID is " 
							+ userTrial.getSetupSurveyUuid(), jsonException);
					throw jsonException;
				}

				// Data Section
				
				List<SurveyResponse> surveyResponses = surveyResponseHandler.getSurveyResponses();
				
				try {
					
					int cycleLength = regimenDuration * 2;
					DateTime surveyDateTime = null;
					
					for(SurveyResponse surveyResponse : surveyResponses) {
						// Calculate the current cycle which is based on the number of days the participant has been participating
						// divided by the cycleLength
						
						surveyDateTime = new DateTime(
							surveyResponse.getEpochMillis()).withZone(DateTimeZone.forID("UTC")).withTime(0, 0, 0, 0);
						
						int daysInTrial = Days.daysBetween(userTrial.getTrialStartDate(), surveyDateTime).getDays();
                        int cycle = (daysInTrial / cycleLength) + 1;
						
						List<PromptResponse> promptResponses = surveyResponse.getPromptResponses();
						JSONObject dataPoint = new JSONObject();
						
						for(PromptResponse promptResponse : promptResponses) {
							
							dataPoint.put("cycle", cycle);
							dataPoint.put("timestamp", 
								dateTimeFormatter.withZone(DateTimeZone.forID(surveyResponse.getTimeZoneString()))
									.print(surveyResponse.getEpochMillis()));
							
							
							if(promptResponse.getPromptId().equals("currentRegimen")) {
								
								dataPoint.put("regimen", regimen(Integer.parseInt(promptResponse.getResponse())));
								
							} else {
								if(! promptResponse.getPromptId().equals("notesAboutToday")) { // notesAboutToday is not relevant to
									                                                           // the analysis, so skip it

									dataPoint.put(promptResponse.getPromptId(), Integer.parseInt(promptResponse.getResponse()));
								} 
							}
						}
						
						dataArray.put(dataPoint);
					}
					
					LOGGER.info(dataArray.length() + " survey responses in the normalized data array");
					
					root.put("data", dataArray);
					userTrial.setNormalizedData(root);
					
				} catch (JSONException dataArrayCreationException) {
					
					LOGGER.error("Could not create an entry in the data array from a survey response.", dataArrayCreationException);
					throw dataArrayCreationException;
				} 

				// Save the data to the DB
				try {
					
					jdbcTemplate.update(SQL_INSERT_TRIAL_DATA_POINTS, userTrial.getUserId(), userTrial.getNormalizedData().toString());
				
				} catch(DataAccessException couldNotInsert) {
					
					LOGGER.error("Could not insert trial data.", couldNotInsert);
					throw couldNotInsert;
				}
			}
		}
		
		// One last loop through the trials to perform and save the analysis results
		
		CloseableHttpClient httpClient = null;
		
		for(UserTrial userTrial : trialsToProcess) {
			LOGGER.info("Processing trial " + userTrial);
			
			httpClient = HttpClients.createDefault();
			HttpPost httpPost = new HttpPost(OCPU_URL);
			httpPost.setHeader("Content-Type", "application/json");
			httpPost.setEntity(new StringEntity(userTrial.getNormalizedData().toString(), Charset.forName("UTF-8")));
			
			StringBuilder ocpuResponseBuilder = new StringBuilder();
			
			try { 
				
				CloseableHttpResponse httpResponse = httpClient.execute(httpPost); 
				
				byte[] bytes = new byte[4096];
				int offset = 0;
				
				InputStream is = httpResponse.getEntity().getContent();
				
				while(is.read(bytes, offset, 4096) != -1) {
					ocpuResponseBuilder.append(new String(bytes));
				}
				
				if(httpResponse.getStatusLine().getStatusCode() != 200) {
					LOGGER.error("Received a non-200 response from OpenCPU: "  + httpResponse.getStatusLine() 
						+ " Returned data: " + ocpuResponseBuilder);
					throw new IllegalStateException("Could not process trial results using OpenCPU.");
				}
				
				is.close();
				httpResponse.close();
				httpClient.close();
				
			} catch(IOException ioException) {
				
				LOGGER.error("Problem with HTTP POST to OpenCPU at " + OCPU_URL, ioException);
				throw ioException;
				
			}
			
			// Now store the results
			try {
				
				// First add the setup survey UUID to the JSON returned from OpenCPU
				// The setup survey UUID is stored with the analysis results in order to determine 
				// whether a particular trial has already been processed. It is stored with the stream
				// data in order to avoid having to create a new table in the DB.
				
				String analysisJsonString = null;
				
				try {
					
					analysisJsonString = new JSONObject(ocpuResponseBuilder.toString()).put("setup_survey_id", userTrial.getSetupSurveyUuid()).toString();
					LOGGER.info("Trial results JSON: " + analysisJsonString);
					
				} catch(JSONException jsonException) {
					
					throw new IllegalStateException("Could not add setup survey ID to JSON returned from OpenCPU", jsonException);
				}
				
				jdbcTemplate.update(SQL_INSERT_TRIAL_ANALYSIS_RESULTS, userTrial.getUserId(), analysisJsonString);
			
			} catch(DataAccessException couldNotInsert) {
				
				LOGGER.error("Could not insert trial results.", couldNotInsert);
				throw couldNotInsert;
			}			
			
			numberOfTrialsProcessed++;
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
	 * Map the number of cycles prompt response  (the <key> element in the prompt's XML config) to the actual number of cycles.
	 * Magic numbers ahoy!
	 */
	private String regimen(int key) {
		if(key == 0) {
			return "A";
		} else if(key == 1) {
			return "B";
		} else {
			throw new IllegalArgumentException("Unknown key for regimen: " + key);
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
				 
				if(alsoReprocessAllTrials) {
					// Any finished trial will be processed
					trialsToProcess.add(userTrial);
					
				} else {
					// Otherwise only trials ending on the end date parameter to this program will be processed
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
		if(! alsoReprocessTrials) {
			// Use an iterator because the list might be modified as it is traversed
			Iterator<UserTrial> iterator = trialsToCheck.iterator();
			while(iterator.hasNext()) {
				UserTrial userTrial = iterator.next();
				if(processedTrials.contains(new ProcessedTrial(userTrial.getUserId(), userTrial.getSetupSurveyUuid()))) {
					iterator.remove();
				}
			}
			return trialsToCheck;
			
		} else {
			return trialsToCheck;
		}
	}
	
	/**
	 * Converts the multi_choice prompt response String into a JSON array of regimens. If the campaignUrn represents a mock trial
	 * or an "old" trial (a test run), returns music genres instead of medications. 
	 */
	private JSONArray regimenArray(JSONObject setupSurvey, String regimenKey, String campaignUrn) throws JSONException {
		boolean isMock = campaignUrn.contains("old") || campaignUrn.contains("mock"); 
		JSONArray stringArray = new JSONArray();
		JSONArray intArray = null;
		try  {
			intArray = new JSONArray(getStringValueForPromptId(setupSurvey, regimenKey));
		} catch (JSONException jsonException) {
			LOGGER.info("Could not retrieve the regimen array from the setup survey using the key " + regimenKey);
		}
		int size = intArray.length();
		for(int i = 0; i < size; i++) {
			stringArray.put(regimenStringForKey(intArray.getInt(i), isMock));
		}
		return stringArray;
	}
	
	/**
	 * Returns the String value for a given regimen key. 
	 */
	private String regimenStringForKey(int key, boolean isMock) throws JSONException {
		if(key == 0) {
			return isMock ? "Classical" : "No specific treatment"; 
		} else if(key == 1) {
			return isMock ? "Country" : "Tylenol (acetaminophen)";
		} else if(key == 2) {
			return isMock ? "Easy Listening" : "Any NSAID (e.g., ibuprofen, naproxen, sulindac)";
		} else if(key == 3) {
			return isMock ? "Folk" : "Codeine combination product (e.g., Tylenol with codeine, Tylenol #3)";
		} else if(key == 4) {
			return isMock ? "Hip hop" : "Tramadol (e.g., Ultram, Ryzolt, ConZip, Rybix)";
		} else if(key == 5) {
			return isMock ? "Jazz" : "Hydrocodone combination product (e.g., Vicodin, Norco)";
		} else if(key == 6) {
			return isMock ? "Pop" : "Oxycodone combination treatment (e.g., Percocet)";
		} else if(key == 7) {
			return isMock ? "Rock" : "Complementary treatment: including but not limited to physical activity (exercise," +
					" stretching, yoga), mindfulness (meditation, relaxation, music therapy)";
		} else if(key == 8) {
			if(isMock) {
				return "Other";
			}
			else {
				throw new JSONException("Found an unknown regimen key for a mock trial. The key value is " + key);
			}
		} else {
			throw new JSONException("Found an unknown regimen key for a mock or real trial. The key value is " + key);
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
				boolean alsoReprocess = false;
				boolean alsoReprocessAll = false;
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
					alsoReprocess = parameters.getBoolean("also-reprocess");
				} catch (JSONException jsonException) {
					LOGGER.error("Boolean value missing for the key 'also-reprocess'.");
					return;
				}
				
				try {
					alsoReprocessAll = parameters.getBoolean("also-reprocess-all");
				} catch (JSONException jsonException) {
					LOGGER.error("Boolean value missing for the key 'also-reprocess-all'.");
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
				
				processor = new TrialistAnalysisProcessor(alsoReprocess, alsoReprocessAll, trialEndDate, campaignUrn);
								
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
		private String uuid;
		private long userId;
		private String surveyId;
		private JSONObject survey;
		
		public UserSurveyDate(final String pUuid, final long pUserId, final String pSurveyId, final JSONObject pSurvey) {
			uuid = pUuid;
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
		
		public String getSurveyUuid() {
			return uuid;
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
		private String setupSurveyUuid;
		private JSONObject normalizedData;
		
		DateTimeFormatter yearMonthDayFormatter;
		
		public UserTrial(final long pUserId, final DateTime pTrialStartDate, final DateTime pTrialEndDate, 
				JSONObject pSetupSurvey, final String pSetupSurveyUuid) {
			userId = pUserId;
			trialStartDate = pTrialStartDate;
			trialEndDate = pTrialEndDate;
			setupSurvey = pSetupSurvey;
			setupSurveyUuid= pSetupSurveyUuid;
			normalizedData = null;
			
			DateTimeFormatterBuilder builder = new DateTimeFormatterBuilder();
			builder.append(ISODateTimeFormat.yearMonthDay().getPrinter(), ISODateTimeFormat.yearMonthDay().getParser());
			yearMonthDayFormatter = builder.toFormatter().withZoneUTC();

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

		public String getSetupSurveyUuid() {
			return setupSurveyUuid;
		}
		
		public JSONObject getNormalizedData() {
			return normalizedData;
		}

		public void setNormalizedData(JSONObject normalizedData) {
			this.normalizedData = normalizedData;
		}
		
		// Omitted setupSurveyPrimary key for less verbose debugging
		@Override
		public String toString() {
			return "UserTrial [userId=" + userId + ", trialStartDate="
					+ yearMonthDayFormatter.print(trialStartDate) + ", trialEndDate=" + yearMonthDayFormatter.print(trialEndDate)
				    + ", setupSurveyUuid=" + setupSurveyUuid + "]";
		}	
	}
	
	/**
	 * A processed trial is identified by a row in observer_stream_data that contains trial analysis results and the UUID of 
	 * the setup survey for a given user.
	 */
	private static class ProcessedTrial {
		private long userId;
		private String surveyUuid;
		
		public ProcessedTrial(final long pUserId, final String pSurveyUuid) {
			userId = pUserId;
			surveyUuid = pSurveyUuid;	
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result
					+ ((surveyUuid == null) ? 0 : surveyUuid.hashCode());
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
			if (surveyUuid == null) {
				if (other.surveyUuid != null)
					return false;
			} else if (!surveyUuid.equals(other.surveyUuid))
				return false;
			if (userId != other.userId)
				return false;
			return true;
		}
	}
	
	/**
	 * Domain object for a single survey response (that contains many prompt responses). 
	 */
	private static class SurveyResponse {
		private long epochMillis;
		private String timeZoneString;
		private List<PromptResponse> promptResponses;
		
		public SurveyResponse(long pEpochMillis, String pTimeZoneString) {
			epochMillis = pEpochMillis;
			timeZoneString = pTimeZoneString;
			promptResponses = new ArrayList<PromptResponse>();
		}
		
		public SurveyResponse(SurveyResponse other) {
			epochMillis = other.getEpochMillis();
			timeZoneString = other.getTimeZoneString();
			promptResponses = new ArrayList<PromptResponse>(other.getPromptResponses());
		}
		
		public void addPromptResponse(PromptResponse response) {
			promptResponses.add(response);
		}
		
		public List<PromptResponse> getPromptResponses() {
			return promptResponses;
		}

		public long getEpochMillis() {
			return epochMillis;
		}

		public String getTimeZoneString() {
			return timeZoneString;
		}
	}
	
	/**
	 * Domain object for a single prompt response. 
	 */
	private static class PromptResponse {
		private String promptId;
		private String response;
		
		public PromptResponse(String pPromptId, String pResponse) {
			promptId = pPromptId;
			response = pResponse;
		}

		public String getPromptId() {
			return promptId;
		}

		public String getResponse() {
			return response;
		}
	}
	
	/**
	 * Callback handler to manage conversion of database rows into SurveyResponse and PromptResponse objects. 
	 */
	private static class SurveyReponseRowCallbackHandler implements RowCallbackHandler {
		private long currentSurveyKey;
		private SurveyResponse currentSurveyResponse = null;
		
		private List<SurveyResponse> surveyResponses;
		
		public SurveyReponseRowCallbackHandler() {
			currentSurveyKey = -1;
			surveyResponses = new ArrayList<SurveyResponse>();
		}
		
		public List<SurveyResponse> getSurveyResponses() {
			return surveyResponses;
		}
		
		/**
		 * For each iteration through the provided ResultSet parameter, pulls out the survey and prompt response data and 
		 * stores it for later retrieval.
		 */
		public void processRow(ResultSet rs) throws SQLException {
			long surveyKey = rs.getLong("id");
			String promptId = rs.getString("prompt_id");
			String response = rs.getString("response");
			long epochMillis = rs.getLong("epoch_millis");
			String timezone = rs.getString("phone_timezone");
						
			if(currentSurveyKey == -1) {
				
				currentSurveyKey = surveyKey;
				currentSurveyResponse = new SurveyResponse(epochMillis, timezone);
				currentSurveyResponse.addPromptResponse(new PromptResponse(promptId, response));
				
			} else if(currentSurveyKey != surveyKey) {
				
				// Save a deep copy of the previous survey to the list
				surveyResponses.add(new SurveyResponse(currentSurveyResponse));
				
				currentSurveyKey = surveyKey;
				currentSurveyResponse = new SurveyResponse(epochMillis, timezone);
				currentSurveyResponse.addPromptResponse(new PromptResponse(promptId, response));
				
			} else if(rs.isLast()) {
				
				currentSurveyResponse.addPromptResponse(new PromptResponse(promptId, response));

				// Save a deep copy of the previous survey to the list
				surveyResponses.add(new SurveyResponse(currentSurveyResponse));
				
			} else {
				
				currentSurveyResponse.addPromptResponse(new PromptResponse(promptId, response));
			}
		}
	}
}
