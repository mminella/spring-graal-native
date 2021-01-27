/*
 * Copyright 2021-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.batch.configuration;

import javax.sql.DataSource;

import com.example.batch.domain.Person;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * @author Michael Minella
 */
@Configuration(proxyBeanMethods = false)
public class BatchConfiguration {

	private static final Log logger = LogFactory.getLog(BatchConfiguration.class);

	@Autowired
	private JobBuilderFactory jobBuilderFactory;

	@Autowired
	private StepBuilderFactory stepBuilderFactory;

	@Bean
	public Job job(Step step1, Step step2) {
		return this.jobBuilderFactory.get("job")
				.start(step1)
				.next(step2)
				.build();
	}

	@StepScope
	@Bean
	public FlatFileItemReader<Person> reader(@Value("#{jobParameters['input']}") Resource inputFile) {
		return new FlatFileItemReaderBuilder<Person>()
				.name("itemReader")
				.resource(inputFile)
				.delimited()
				.names("firstName", "lastName")
				.targetType(Person.class)
				.build();

	}

	@JobScope
	@Bean
	public ItemProcessor<Person, Person> itemProcessor() {
		return person -> new Person(person.getFirstName(), person.getLastName().toUpperCase());
	}

	@Bean
	public JdbcBatchItemWriter<Person> writer(DataSource dataSource) {
		return new JdbcBatchItemWriterBuilder<Person>()
				.dataSource(dataSource)
				.beanMapped()
				.sql("INSERT INTO PERSON VALUES (:firstName, :lastName)")
				.build();
	}

	@Bean
	public Step step1(ItemReader<Person> itemReader, ItemProcessor<Person, Person> itemProcessor, ItemWriter<Person> itemWriter) {
		return this.stepBuilderFactory.get("step1")
				.<Person, Person>chunk(2)
				.reader(itemReader)
				.processor(itemProcessor)
				.writer(itemWriter)
				.build();
	}

	@Bean
	public Step step2() {
		return this.stepBuilderFactory.get("step2")
				.tasklet((stepContribution, chunkContext) -> {
					System.out.println("Batch IO ran!");
					logger.info("INFO log message");
					return RepeatStatus.FINISHED;
				}).build();
	}
}
