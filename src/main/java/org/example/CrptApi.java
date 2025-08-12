package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class CrptApi {

    private static final String URL = "https://ismp.crpt.ru/api/v3/lk/documents/create";
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss-SSS")
                    .withZone(ZoneId.systemDefault());

    private final long timeLimitMs;
    private final int requestLimit;
    private final List<TimeRequest> timeRequests = new ArrayList<>();
    private final Object lock = new Object();

    public CrptApi(long timeLimit, TimeUnit timeUnit, int requestLimit) {
        if (timeUnit == null) {
            throw new IllegalArgumentException("TimeUnit cannot be null");
        }
        if (timeLimit <= 0) {
            throw new IllegalArgumentException("Time limit must be positive");
        }
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("Request limit must be greater than 0");
        }

        this.timeLimitMs = timeUnit.toMillis(timeLimit);
        this.requestLimit = requestLimit;
    }

    public static void main(String[] args) {
        int iThread = 1;

        CrptApi crptApi = new CrptApi(5, TimeUnit.SECONDS, 10);

        for (int i = 0; i < 20; i++) {
            Thread newThread = new Thread(new CrptThread(crptApi, iThread));
            newThread.setName("Thread " + iThread);
            newThread.start();
            iThread++;
        }
    }

    public void createDocument(int prefix) {

        /* Формирование запроса */
        var timeRequest = addTimeRequest();

        /* Ожидание очереди */
        printPauseMessage(timeRequest.getWaitingMillis());
        pauseThread(timeRequest.getWaitingMillis());

        /* Создание документа */
        long startMillis = System.currentTimeMillis();
        createDocument(prefix, Thread.currentThread().getName());
        long endMillis = System.currentTimeMillis();

        /* Пауза для соблюдения лимита */
        long pauseMillis = timeLimitMs - (endMillis - startMillis);
        pauseThread(Math.max(pauseMillis, 0));

        /* Выход запроса из очереди */
        synchronized (lock) {
            timeRequests.remove(0);
        }
    }

    private synchronized TimeRequest addTimeRequest() {
        long currentMillis = System.currentTimeMillis();
        long waitingMillis = calculateWaitingMillis(currentMillis);
        var timeRequest = new TimeRequest(currentMillis, waitingMillis);
        timeRequests.add(timeRequest);
        return timeRequest;
    }

    private void printPauseMessage(long waitingMillis) {
        System.out.printf("%s. %s: Pause %d ms...\n",
                getDataFromMillis(System.currentTimeMillis()),
                Thread.currentThread().getName(), waitingMillis);
    }

    private void pauseThread(long waitingMillis) {
        if (waitingMillis > 0) {
            try {
                Thread.sleep(waitingMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Thread interrupted", e);
            }
        }
    }

    private synchronized long calculateWaitingMillis(long creationTimeMs) {
        if (timeRequests.isEmpty() || timeRequests.size() < requestLimit) {
            return 0;
        }

        int iOldRequest = timeRequests.size() - requestLimit;
        TimeRequest oldRequest = timeRequests.get(iOldRequest);

        long difference = creationTimeMs - oldRequest.getCreationMillis();
        long waitingMillis = timeLimitMs - difference + oldRequest.decreaseWaitingMillis(difference);

        return Math.max(waitingMillis, 0);
    }

    private void createDocument(int prefix, String threadName) {
        var product = createProduct(prefix);
        var description = new Description(Integer.toString(prefix));
        var document = createDocument(prefix, description, List.of(product));
        String signature = "<Sign " + prefix + ">";

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(URL);
            httpPost.setHeader("Content-Type", "application/json");

            ObjectMapper objectMapper = new ObjectMapper();

            Map<String, Object> requestBodyMap = new HashMap<>();
            requestBodyMap.put("documentFormat", "MANUAL");
            requestBodyMap.put("productDocument", document);
            requestBodyMap.put("type", "TYPE");
            requestBodyMap.put("signature", signature);

            String requestBody = objectMapper.writeValueAsString(requestBodyMap);
            httpPost.setEntity(new StringEntity(requestBody));

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode >= 200 && statusCode <= 299) {
                    String responseBody = EntityUtils.toString(response.getEntity());

                    JsonNode rootNode = objectMapper.readTree(responseBody);
                    String docId = rootNode
                            .path("productDocument")
                            .path("docId")
                            .asText();

                    System.out.printf("%s. %s: The document with ID = %s is created!\n",
                            getDataFromMillis(System.currentTimeMillis()), threadName, docId);
                } else {
                    System.out.printf("%s: Error %d. Response: %s\n",
                            threadName, statusCode, EntityUtils.toString(response.getEntity()));
                }
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }

    private String getDataFromMillis(long millis) {
        return DATE_FORMATTER.format(Instant.ofEpochMilli(millis));
    }

    private Product createProduct(int prefix) {
        return new Product(
                "cert" + prefix,
                "2025-08-11",
                "certDocNumber",
                "owner",
                "producer",
                "2025-08-11",
                "tnved1",
                "uitCode",
                "uituCode"
        );
    }

    private Document createDocument(int prefix, Description description, List<Product> products) {
        return new Document(
                description,
                Integer.toString(prefix),
                "approved",
                "type",
                true,
                "ownerInn",
                "participantInn",
                "producerInn",
                "2025-08-11",
                "type",
                products,
                "2025-08-11",
                "regType"
        );
    }
}

@Data
@RequiredArgsConstructor
class TimeRequest {

    private final long creationMillis;
    private final long waitingMillis;

    public long decreaseWaitingMillis(long value) {
        long result = waitingMillis - value;
        return Math.max(result, 0);
    }
}

@Data
@RequiredArgsConstructor
class CrptThread implements Runnable {

    private final CrptApi crptApi;
    private final int namePrefix;

    public void run() {
        crptApi.createDocument(namePrefix);
    }
}

@Data
@AllArgsConstructor
class Document {

    private Description description;
    private String docId;
    private String docStatus;
    private String docType;
    private boolean importRequest;
    private String ownerInn;
    private String participantInn;
    private String producerInn;
    private String productionDate;
    private String productionType;
    private List<Product> products;
    private String regDate;
    private String regNumber;
}

@Data
@AllArgsConstructor
class Description {

    private String participantInn;
}

@Data
@AllArgsConstructor
class Product {

    private String certificateDocument;
    private String certificateDocumentDate;
    private String certificateDocumentNumber;
    private String ownerInn;
    private String producerInn;
    private String productionDate;
    private String tnvedCode;
    private String uitCode;
    private String uituCode;
}
