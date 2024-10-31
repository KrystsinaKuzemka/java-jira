package com.jira.automation.jira_automation_java;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class JiraAutomationJavaApplication {

	public static void main(String[] args) {
		SpringApplication.run(JiraAutomationJavaApplication.class, args);
		System.out.println("Serwer zostal uruchomiony na porcie 3000");
	}
}
