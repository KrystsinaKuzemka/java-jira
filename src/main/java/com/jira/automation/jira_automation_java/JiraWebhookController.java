package com.jira.automation.jira_automation_java;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.stereotype.Controller;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

@RestController
public class JiraWebhookController {

    @Value("${jira.api.key}")
    private String jiraApiKey;

    @Value("${jira.url}")
    private String jiraURL;

    @Value("${jira.email}")
    private String jiraEmail;

    @Value("${mailing.login}")
    private String mailingLogin;

    @Value("${mailing.password}")
    private String mailingPassword;

    private final JavaMailSender mailSender;

    public JiraWebhookController(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    private List<String> priorityKeywordsArray = Arrays.asList(
            "asap", "critical", "severe", "immediate attention",
            "outage", "high priority", "system down", "escalation",
            "blocking", "impacting", "major incident", "urgent"
    );

    private List<Map<String, String>> users = Arrays.asList(
            Map.of("email", "k.v.kuzemko@gmail.com")
    );

    @PostMapping("/")
    public ResponseEntity<String> handleWebhook(@RequestBody Map<String, Object> data) {

        try {
            String webhookEvent = (String) data.get("webhookEvent");
            Map<String, Object> issue = (Map<String, Object>) data.get("issue");
            Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
            String issueId = (String) issue.get("id");
            Map<String, Object> changelog = (Map<String, Object>) data.get("changelog");


            if (changelog.get("items") != null) {
                List<Map<String, Object>> items = (List<Map<String, Object>>) changelog.get("items");
                if (!items.isEmpty()) {
                    Map<String, Object> item = items.get(0);
                    String fromString = (String) item.get("fromString");
                    String toString = (String) item.get("toString");

                    if (Objects.equals(fromString, toString)) {
                        return ResponseEntity.ok("No changes detected, exiting.");
                    }
                }
            }

            if ("jira:issue_created".equals(webhookEvent)) {
                String issueType = (String) ((Map<String, Object>) fields.get("issuetype")).get("name");
                if (!"Bug".equals(issueType)) {
                    workBalancer(issueId);
                }
            }

            if ("jira:issue_updated".equals(webhookEvent) || "jira:issue_created".equals(webhookEvent)) {
                if (changelog != null && changelog.get("items") != null) {
                    List<Map<String, Object>> items = (List<Map<String, Object>>) changelog.get("items");
                    if (!items.isEmpty()) {
                        Map<String, Object> item = items.get(0);
                        if (!Objects.equals(item.get("fromString"), item.get("toString"))) {
                            checkTasks();
                            allTaskClosed();
                        }
                    }
                }

                Map<String, Object> priorityField = (Map<String, Object>) fields.get("priority");
                if (priorityField != null && !"High".equals(priorityField.get("name"))) {
                    String description = (String) fields.get("description");
                    String summary = (String) fields.get("summary");

                    for (String keyword : priorityKeywordsArray) {
                        if ((description != null && description.toLowerCase().contains(keyword.toLowerCase())) ||
                                (summary != null && summary.toLowerCase().contains(keyword.toLowerCase()))) {
                            changePriority(issueId);
                            break;
                        }
                    }
                }
            }

            if ("jira:issue_updated".equals(webhookEvent)) {
                Map<String, Object> parent = (Map<String, Object>) fields.get("parent");
                if (parent != null && parent.get("id") != null && !((String) parent.get("id")).isEmpty()) {
                    checkParentTask((String) parent.get("id"));
                } else {
                    checkSubtasks(issueId, data);
                }
            }

            if ("jira:issue_created".equals(webhookEvent) || "jira:issue_updated".equals(webhookEvent)) {
                String description = (String) fields.get("description");
                if ("bug".equalsIgnoreCase(description)) {
                    assignTask("bug", issueId);
                }
                findDuplicates();
            }

            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            logError(e, data);
            return ResponseEntity.status(500).body("Error occurred");
        }
    }

    private HttpHeaders createAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        String auth = jiraEmail + ":" + jiraApiKey;
        byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes());
        String authHeader = "Basic " + new String(encodedAuth);

        headers.set("Authorization", authHeader);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        return headers;
    }

    private void checkParentTask(String parentId) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            String url = jiraURL + "/rest/api/3/issue/" + parentId;

            HttpEntity<String> requestEntity = new HttpEntity<>(createAuthHeaders());
            ResponseEntity<Map> responseEntity = restTemplate.exchange(url, HttpMethod.GET, requestEntity, Map.class);

            Map<String, Object> response = responseEntity.getBody();
            Map<String, Object> fields = (Map<String, Object>) response.get("fields");
            List<Map<String, Object>> subtasks = (List<Map<String, Object>>) fields.get("subtasks");
            checkSubtasksUsingParentID(parentId, subtasks);
        } catch (Exception e) {
            System.err.println("Wystapil blad: " + e.getMessage());
        }
    }

    private void checkSubtasksUsingParentID(String issueId, List<Map<String, Object>> subtasks) {
        if (subtasks == null || subtasks.isEmpty()) {
            System.out.println("Brak subtaskow");
            return;
        }

        boolean allCompleted = true;
        for (Map<String, Object> subtask : subtasks) {
            Map<String, Object> fields = (Map<String, Object>) subtask.get("fields");
            Map<String, Object> status = (Map<String, Object>) fields.get("status");
            if (!"Gotowe".equals(status.get("name"))) {
                allCompleted = false;
                break;
            }
        }

        if (allCompleted) {
            try {
                RestTemplate restTemplate = new RestTemplate();
                String url = jiraURL + "/rest/api/3/issue/" + issueId + "/transitions";

                Map<String, Object> body = Map.of("transition", Map.of("id", "31")); // Id dla gotowe
                HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, createAuthHeaders());

                restTemplate.exchange(url, HttpMethod.POST, requestEntity, String.class);
                System.out.println("Zmieniono status na gotowe 'Gotowe'");
            } catch (Exception e) {
                System.err.println("Błąd przy zmianie statusu: " + e.getMessage());
            }
        }
    }

    private void checkSubtasks(String issueId, Map<String, Object> data) {
        Map<String, Object> issue = (Map<String, Object>) data.get("issue");
        Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
        Map<String, Object> status = (Map<String, Object>) fields.get("status");

        if ("Gotowe".equals(status.get("name"))) {
            System.out.println("Task ma juz status 'Gotowe'");
            return;
        }

        List<Map<String, Object>> subtasks = (List<Map<String, Object>>) fields.get("subtasks");
        if (subtasks == null || subtasks.isEmpty()) {
            System.out.println("Brak subtaskow");
            return;
        }

        boolean allCompleted = true;
        for (Map<String, Object> subtask : subtasks) {
            Map<String, Object> subtaskFields = (Map<String, Object>) subtask.get("fields");
            Map<String, Object> subtaskStatus = (Map<String, Object>) subtaskFields.get("status");
            if (!"Gotowe".equals(subtaskStatus.get("name"))) {
                allCompleted = false;
                break;
            }
        }

        if (allCompleted) {
            try {
                RestTemplate restTemplate = new RestTemplate();
                String url = jiraURL + "/rest/api/3/issue/" + issueId + "/transitions";

                Map<String, Object> body = Map.of("transition", Map.of("id", "31")); // Id dla gotowe
                HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, createAuthHeaders());

                restTemplate.exchange(url, HttpMethod.POST, requestEntity, String.class);
                System.out.println("Zmieniono status taska na 'Gotowe'");
            } catch (Exception e) {
                System.err.println("Blad przy zmianie statusu: " + e.getMessage());
            }
        }
    }

    private void closeSubtask(String subtaskId) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            String url = jiraURL + "/rest/api/3/issue/" + subtaskId + "/transitions";

            Map<String, Object> body = Map.of("transition", Map.of("id", "31")); // Id dla gotowe
            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, createAuthHeaders());

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                System.out.println("Zamknieto task o id: " + subtaskId);
            } else {
                System.err.println("Nie udalo sie zamknac taska o id: " + subtaskId + ". Status: " + response.getStatusCode());
            }
        } catch (Exception e) {
            System.err.println("Blad podczas zamykania taska: " + e.getMessage());
        }
    }

    private void checkTasks() {
        try {
            RestTemplate restTemplate = new RestTemplate();
            String url = jiraURL + "/rest/api/3/search?maxResults=1000";

            HttpEntity<String> requestEntity = new HttpEntity<>(createAuthHeaders());
            ResponseEntity<Map> responseEntity = restTemplate.exchange(url, HttpMethod.GET, requestEntity, Map.class);

            Map<String, Object> response = responseEntity.getBody();
            List<Map<String, Object>> issues = (List<Map<String, Object>>) response.get("issues");

            for (Map<String, Object> issue : issues) {
                String issueId = (String) issue.get("id");
                Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
                Map<String, Object> status = (Map<String, Object>) fields.get("status");
                List<Map<String, Object>> subtasks = (List<Map<String, Object>>) fields.get("subtasks");

                if ("Gotowe".equals(status.get("name")) && subtasks != null && !subtasks.isEmpty()) {
                    for (Map<String, Object> subtask : subtasks) {
                        String subtaskId = (String) subtask.get("id");
                        Map<String, Object> subtaskFields = (Map<String, Object>) subtask.get("fields");
                        Map<String, Object> subtaskStatus = (Map<String, Object>) subtaskFields.get("status");

                        if (!"Gotowe".equals(subtaskStatus.get("name"))) {
                            closeSubtask(subtaskId);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Blad podczas sprawdzania taska: " + e.getMessage());
        }
    }

    private void allTaskClosed() {
        try {
            RestTemplate restTemplate = new RestTemplate();
            String url = jiraURL + "/rest/api/3/search?maxResults=1000";

            HttpEntity<String> requestEntity = new HttpEntity<>(createAuthHeaders());
            ResponseEntity<Map> responseEntity = restTemplate.exchange(url, HttpMethod.GET, requestEntity, Map.class);

            Map<String, Object> response = responseEntity.getBody();
            List<Map<String, Object>> issues = (List<Map<String, Object>>) response.get("issues");

            boolean allDone = issues.stream().allMatch(issue -> {
                Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
                Map<String, Object> status = (Map<String, Object>) fields.get("status");
                String statusName = (String) status.get("name");
                return "Done".equals(statusName) || "Gotowe".equals(statusName);
            });

            if (allDone) {
                System.out.println("Wysylanie raportu");
                generateReport();
                sendMails(users);
            }
        } catch (Exception e) {
            System.err.println("Wystapil blad: " + e.getMessage());
        }
    }

    private void sendMails(List<Map<String, String>> users) {
        String emailList = String.join(",", users.stream().map(u -> u.get("email")).toList());

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);

            helper.setFrom(mailingLogin);
            helper.setTo(emailList);
            helper.setSubject("Jira automation report");
            helper.setText("Report", true);

            helper.addAttachment("jira_report.csv", new File("jira_report.csv"));
            helper.addAttachment("jira_report.json", new File("jira_report.json"));

            mailSender.send(message);
            System.out.println("Wyslano raport na maila: " + emailList);
        } catch (MessagingException e) {
            System.err.println("Blad podczas wysylania raportu na maila: " + e.getMessage());
        }
    }

    private void generateReport() {
        try {
            RestTemplate restTemplate = new RestTemplate();
            String jqlQuery = "";
            String fields = "summary,assignee,status,priority,created,updated";
            int maxResults = 100;

            String url = jiraURL + "/rest/api/3/search?jql=" + jqlQuery + "&fields=" + fields + "&maxResults=" + maxResults;

            HttpEntity<String> requestEntity = new HttpEntity<>(createAuthHeaders());
            ResponseEntity<Map> responseEntity = restTemplate.exchange(url, HttpMethod.GET, requestEntity, Map.class);

            Map<String, Object> response = responseEntity.getBody();
            List<Map<String, Object>> issues = (List<Map<String, Object>>) response.get("issues");
            List<Map<String, Object>> report = new ArrayList<>();

            for (Map<String, Object> issue : issues) {
                Map<String, Object> fieldsMap = (Map<String, Object>) issue.get("fields");
                Map<String, Object> reportItem = new HashMap<>();
                reportItem.put("summary", fieldsMap.get("summary"));
                Map<String, Object> assignee = (Map<String, Object>) fieldsMap.get("assignee");
                reportItem.put("assignee", assignee != null ? assignee.get("displayName") : "Unassigned");
                Map<String, Object> status = (Map<String, Object>) fieldsMap.get("status");
                reportItem.put("status", status != null ? status.get("name") : "Unknown");
                Map<String, Object> priority = (Map<String, Object>) fieldsMap.get("priority");
                reportItem.put("priority", priority != null ? priority.get("name") : "None");
                reportItem.put("created", fieldsMap.get("created"));
                reportItem.put("updated", fieldsMap.get("updated"));
                report.add(reportItem);
            }

            try (FileWriter jsonWriter = new FileWriter("jira_report.json")) {
                jsonWriter.write(report.toString());
            }

            try (FileWriter csvWriter = new FileWriter("jira_report.csv")) {
                csvWriter.write("Summary,Assignee,Status,Priority,Created,Updated\n");
                for (Map<String, Object> issue : report) {
                    csvWriter.write(String.format("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"\n",
                            issue.get("summary"),
                            issue.get("assignee"),
                            issue.get("status"),
                            issue.get("priority"),
                            issue.get("created"),
                            issue.get("updated")));
                }
            }

            System.out.println("Wygenerowano raport i zapisano jako jira_report.json i jira_report.csv");
        } catch (Exception e) {
            System.err.println("Blad podczas generowania raportu: " + e.getMessage());
        }
    }

    private void workBalancer(String issueId) {
        try {
            RestTemplate restTemplate = new RestTemplate();

            String jqlQuery = "status != Done";
            String taskUrl = jiraURL + "/rest/api/3/search?jql=" + jqlQuery + "&maxResults=1000";
            HttpEntity<String> requestEntity = new HttpEntity<>(createAuthHeaders());
            ResponseEntity<Map> taskResponse = restTemplate.exchange(taskUrl, HttpMethod.GET, requestEntity, Map.class);

            List<Map<String, Object>> issues = (List<Map<String, Object>>) taskResponse.getBody().get("issues");
            Map<String, UserTaskInfo> userTaskCounts = new HashMap<>();

            for (Map<String, Object> issue : issues) {
                Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
                Map<String, Object> assignee = (Map<String, Object>) fields.get("assignee");
                if (assignee != null) {
                    String userId = (String) assignee.get("accountId");
                    String displayName = (String) assignee.get("displayName");
                    userTaskCounts.computeIfAbsent(userId,
                            k -> new UserTaskInfo(userId, displayName)).incrementTaskCount();
                }
            }

            if (userTaskCounts.isEmpty()) {
                System.out.println("Nie znaleziono uzytkownikow przypisanych do zadan");
                return;
            }

            UserTaskInfo userWithMinTasks = userTaskCounts.values().stream()
                    .min(Comparator.comparingInt(UserTaskInfo::getTaskCount))
                    .orElseThrow(() -> new RuntimeException("Nie znaleziono uzytkownikow"));

            System.out.println("Aktualny rozklad zadan:");
            userTaskCounts.values().forEach(info ->
                    System.out.println(info.getDisplayName() + ": " + info.getTaskCount() + " tasks")
            );

            assignTaskWithWorkBalancer(userWithMinTasks.getUserId(), issueId);

            System.out.println("Przypisano nowe zadanie do: " + userWithMinTasks.getDisplayName());

        } catch (Exception e) {
            System.err.println("Wystapil blad podczas balansowania pracy: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static class UserTaskInfo {
        private final String userId;
        private final String displayName;
        private int taskCount;

        public UserTaskInfo(String userId, String displayName) {
            this.userId = userId;
            this.displayName = displayName;
            this.taskCount = 0;
        }

        public void incrementTaskCount() {
            taskCount++;
        }

        public int getTaskCount() {
            return taskCount;
        }

        public String getUserId() {
            return userId;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private void assignTaskWithWorkBalancer(String userId, String issueId) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            String url = jiraURL + "/rest/api/3/issue/" + issueId + "/assignee";

            Map<String, Object> body = Map.of("accountId", userId);
            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, createAuthHeaders());

            restTemplate.exchange(url, HttpMethod.PUT, requestEntity, String.class);
            System.out.println("Przypisano task do uzytkownika o id: " + userId);
        } catch (Exception e) {
            System.err.println("Blad podczas przypisywania taska: " + e.getMessage());
        }
    }

    private void findDuplicates() {
        try {
            RestTemplate restTemplate = new RestTemplate();
            String url = jiraURL + "/rest/api/3/search?maxResults=1000&status!=done";

            HttpEntity<String> requestEntity = new HttpEntity<>(createAuthHeaders());
            ResponseEntity<Map> responseEntity = restTemplate.exchange(url, HttpMethod.GET, requestEntity, Map.class);

            Map<String, Object> response = responseEntity.getBody();
            List<Map<String, Object>> issuesArr = (List<Map<String, Object>>) response.get("issues");
            List<Map<String, Object>> compareArr = issuesArr;

            Set<String> duplicatedIssues = new HashSet<>();

            for (Map<String, Object> el : issuesArr) {
                String issueType = el.get("fields") != null && ((Map<String, Object>) el.get("fields")).get("issuetype") != null ?
                        (String) ((Map<String, Object>) ((Map<String, Object>) el.get("fields")).get("issuetype")).get("name") : null;
                String issueSummary = (String) ((Map<String, Object>) el.get("fields")).get("summary");

                Map<String, Object> descriptionMap = (Map<String, Object>) ((Map<String, Object>) el.get("fields")).get("description");
                String issueDescription = null;
                if (descriptionMap != null && descriptionMap.get("content") != null) {
                    List<Map<String, Object>> contentList = (List<Map<String, Object>>) descriptionMap.get("content");
                    if (!contentList.isEmpty()) {
                        Map<String, Object> firstContent = contentList.get(0);
                        if (firstContent.get("content") != null) {
                            List<Map<String, Object>> innerContentList = (List<Map<String, Object>>) firstContent.get("content");
                            if (!innerContentList.isEmpty()) {
                                Map<String, Object> innerContent = innerContentList.get(0);
                                issueDescription = (String) innerContent.get("text");
                            }
                        }
                    }
                }

                String issuePriority = ((Map<String, Object>) el.get("fields")).get("priority") != null ?
                        (String) ((Map<String, Object>) ((Map<String, Object>) el.get("fields")).get("priority")).get("name") : null;
                String issueId = (String) el.get("id");

                for (Map<String, Object> comparedEl : compareArr) {
                    String comparedType = comparedEl.get("fields") != null && ((Map<String, Object>) comparedEl.get("fields")).get("issuetype") != null ?
                            (String) ((Map<String, Object>) ((Map<String, Object>) comparedEl.get("fields")).get("issuetype")).get("name") : null;
                    String comparedSummary = (String) ((Map<String, Object>) comparedEl.get("fields")).get("summary");

                    Map<String, Object> comparedDescriptionMap = (Map<String, Object>) ((Map<String, Object>) comparedEl.get("fields")).get("description");
                    String comparedDescription = null;
                    if (comparedDescriptionMap != null && comparedDescriptionMap.get("content") != null) {
                        List<Map<String, Object>> comparedContentList = (List<Map<String, Object>>) comparedDescriptionMap.get("content");
                        if (!comparedContentList.isEmpty()) {
                            Map<String, Object> firstComparedContent = comparedContentList.get(0);
                            if (firstComparedContent.get("content") != null) {
                                List<Map<String, Object>> innerComparedContentList = (List<Map<String, Object>>) firstComparedContent.get("content");
                                if (!innerComparedContentList.isEmpty()) {
                                    Map<String, Object> innerComparedContent = innerComparedContentList.get(0);
                                    comparedDescription = (String) innerComparedContent.get("text");
                                }
                            }
                        }
                    }

                    String comparedPriority = ((Map<String, Object>) comparedEl.get("fields")).get("priority") != null ?
                            (String) ((Map<String, Object>) ((Map<String, Object>) comparedEl.get("fields")).get("priority")).get("name") : null;
                    String comparedId = (String) comparedEl.get("id");

                    if (!issueId.equals(comparedId) &&
                            Objects.equals(issueType, comparedType) &&
                            Objects.equals(issueSummary, comparedSummary) &&
                            Objects.equals(issueDescription, comparedDescription) &&
                            Objects.equals(issuePriority, comparedPriority)) {
                        duplicatedIssues.add(comparedId);
                    }
                }
            }

            if (!duplicatedIssues.isEmpty()) {
                deleteDuplicates(duplicatedIssues);
            }

        } catch (Exception e) {
            System.err.println("Wystapil blad: " + e.getMessage());
        }
    }



    private void deleteDuplicates(Set<String> duplicatedIssues) {
        // Przechodzimy przez elementy, pomijając pierwszy (skip(1))
        duplicatedIssues.stream().skip(1).forEach(issueId -> {
            try {
                RestTemplate restTemplate = new RestTemplate();
                String url = jiraURL + "/rest/api/3/issue/" + issueId;

                HttpEntity<String> requestEntity = new HttpEntity<>(createAuthHeaders());
                restTemplate.exchange(url, HttpMethod.DELETE, requestEntity, String.class);

                System.out.println("Usunieto duplikaty: " + issueId);
            } catch (Exception e) {
                System.err.println("Wystaplil blad podczas usuwania duplikatow: " + e.getMessage());
            }
        });
    }


    private void changePriority(String issueId) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            String url = jiraURL + "/rest/api/3/issue/" + issueId;

            Map<String, Object> body = Map.of("fields", Map.of("priority", Map.of("id", "2"))); // Id dla priorytetu "High"
            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, createAuthHeaders());

            restTemplate.exchange(url, HttpMethod.PUT, requestEntity, String.class);
            System.out.println("Zmieniono priorytet na 'High'");
        } catch (Exception e) {
            System.err.println("Blad podczas zmiany priorytetu: " + e.getMessage());
        }
    }

    private void assignTask(String type, String issueId) {
        if ("bug".equalsIgnoreCase(type)) {
            try {
                RestTemplate restTemplate = new RestTemplate();
                String url = jiraURL + "/rest/api/3/issue/" + issueId + "/assignee";

                String id = "712020:180dda81-8e80-43ad-aee6-537b38836786"; // Id dla Gedeona
                Map<String, Object> body = Map.of("accountId", id);
                HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, createAuthHeaders());

                restTemplate.exchange(url, HttpMethod.PUT, requestEntity, String.class);
                System.out.println("Przypisano task do odpowiedniego uzytkownika");
            } catch (Exception e) {
                System.err.println("Blad podczas przypisywania: " + e.getMessage());
            }
        }
    }

    private void logError(Exception e, Map<String, Object> requestBody) {
        System.err.println("Pojawil sie blad: " + e.getMessage());
        try (FileWriter writer = new FileWriter("last_error.txt")) {
            writer.write("Error: " + e.getMessage() + "\n");
            writer.write("Webhook data: " + requestBody.toString());
        } catch (IOException ioException) {
            System.err.println("Blad podczas zapisywania logu: " + ioException.getMessage());
        }
    }
}
