-- --------------------------------------------------------------------
-- A lookup table for processed Trialist trials. Provides a historical
-- record of processed trials.
-- --------------------------------------------------------------------
CREATE TABLE `trialist_processed_trial` (
    `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
    `user_id` int unsigned NOT NULL,
    `setup_survey_response_id` int unsigned NOT NULL,
    last_modified_timestamp timestamp DEFAULT now(),
    PRIMARY KEY (`id`),
    CONSTRAINT `trialist_processed_trial_fk_user`
        FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
   CONSTRAINT `trialist_processed_trial_fk_survey_response`
        FOREIGN KEY (`setup_survey_response_id`) REFERENCES `survey_response` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
