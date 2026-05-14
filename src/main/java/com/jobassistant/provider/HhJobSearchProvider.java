package com.jobassistant.provider;

import com.jobassistant.dto.WorkFormat;
import com.jobassistant.dto.request.JobSearchRequest;
import com.jobassistant.dto.result.JobSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class HhJobSearchProvider implements JobSearchProvider {

    private static final String BASE_URL = "https://hh.ru";

    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";

    private static final int TIMEOUT_MS = 15_000;

    private static final int MAX_PAGES = 1;

    private static final boolean LOAD_DETAILS = true;

    @Override
    public String getProviderName() {
        return "HeadHunter";
    }

    @Override
    public boolean isAvailable() {
        try {
            Connection.Response response = Jsoup.connect(BASE_URL)
                    .userAgent(BROWSER_USER_AGENT)
                    .referrer("https://www.google.com")
                    .timeout(TIMEOUT_MS)
                    .ignoreHttpErrors(true)
                    .followRedirects(true)
                    .execute();

            int statusCode = response.statusCode();

            if (statusCode >= 200 && statusCode < 400) {
                return true;
            }

            log.warn("hh.ru вернул HTTP {}", statusCode);
            return false;

        } catch (Exception e) {
            log.warn("hh.ru недоступен: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public List<JobSearchResult> search(JobSearchRequest request) {
        List<JobSearchResult> results = new ArrayList<>();

        if (request == null || isBlank(request.query())) {
            log.warn("Пустой поисковый запрос для hh.ru");
            return results;
        }

        String encodedQuery = URLEncoder.encode(request.query(), StandardCharsets.UTF_8);

        for (int page = 0; page < MAX_PAGES; page++) {
            try {
                String url = BASE_URL + "/search/vacancy"
                        + "?text=" + encodedQuery
                        + "&page=" + page;

                Document doc = loadDocument(url);

                Elements cards = doc.select(
                        "[data-qa='vacancy-serp__vacancy'], "
                                + ".vacancy-serp-item, "
                                + ".serp-item"
                );

                log.info("hh.ru: страница {}, найдено карточек: {}", page, cards.size());

                for (Element card : cards) {
                    JobSearchResult result = parseCard(card);

                    if (result != null) {
                        results.add(result);
                    }

                    politeDelay();
                }

            } catch (Exception e) {
                log.error("Ошибка парсинга hh.ru, страница {}: {}", page, e.getMessage(), e);
            }
        }

        log.info("hh.ru: всего найдено {} вакансий", results.size());
        return results;
    }

    private JobSearchResult parseCard(Element card) {
        try {
            Element titleElement = firstElement(
                    card,
                    "[data-qa='serp-item__title']",
                    "[data-qa='vacancy-serp__vacancy-title']",
                    "a[href*='/vacancy/']"
            );

            String title = textOrDefault(titleElement, "Без названия");

            String link = "";
            if (titleElement != null) {
                link = titleElement.attr("abs:href");

                if (isBlank(link)) {
                    link = absoluteUrl(titleElement.attr("href"));
                }
            }

            String externalId = extractExternalId(link);

            Element companyElement = firstElement(
                    card,
                    "[data-qa='vacancy-serp__vacancy-employer']",
                    "[data-qa='vacancy-serp__vacancy-employer-text']",
                    "a[href*='/employer/']"
            );

            String company = textOrDefault(companyElement, "Не указана");

            String salaryText = firstText(
                    card,
                    "[data-qa='vacancy-serp__vacancy-compensation']",
                    "[data-qa='vacancy-serp__vacancy-salary']",
                    "[class*=compensation]",
                    "[class*=salary]"
            );

            String location = firstText(
                    card,
                    "[data-qa='vacancy-serp__vacancy-address']",
                    "[data-qa='vacancy-serp__vacancy-address'] span",
                    "[class*=address]"
            );

            String requirements = firstText(
                    card,
                    "[data-qa='vacancy-serp__vacancy_snippet_requirement']"
            );

            String responsibilities = firstText(
                    card,
                    "[data-qa='vacancy-serp__vacancy_snippet_responsibility']"
            );

            String cardText = cleanText(card.text());

            String experience = extractExperience(cardText);
            WorkFormat workFormat = extractWorkFormat(cardText);

            String employment = "";
            String schedule = "";

            Set<String> skills = extractSkills(card);

            VacancyDetails details = VacancyDetails.empty();

            if (LOAD_DETAILS && !isBlank(link)) {
                details = loadVacancyDetails(link);

                if (isBlank(salaryText)) {
                    salaryText = details.salaryText();
                }

                if (isBlank(location)) {
                    location = details.location();
                }

                if (isBlank(experience)) {
                    experience = details.experience();
                }

                if (workFormat == WorkFormat.UNKNOWN) {
                    workFormat = details.workFormat();
                }

                if (isBlank(employment)) {
                    employment = details.employment();
                }

                if (isBlank(schedule)) {
                    schedule = details.schedule();
                }

                if (isBlank(requirements)) {
                    requirements = details.requirements();
                }

                if (isBlank(responsibilities)) {
                    responsibilities = details.responsibilities();
                }

                skills.addAll(details.skills());
            }

            SalaryInfo salaryInfo = parseSalary(salaryText);

            String description = !isBlank(details.description())
                    ? details.description()
                    : buildShortDescription(requirements, responsibilities, cardText);

            return JobSearchResult.builder()
                    .externalId(externalId)
                    .source("HeadHunter")
                    .sourceUrl(link)
                    .position(title)
                    .companyName(company)
                    .location(location)
                    .workFormat(workFormat)
                    .salaryFrom(salaryInfo.from())
                    .salaryTo(salaryInfo.to())
                    .currency(salaryInfo.currency())
                    .salaryGross(salaryInfo.gross())
                    .experience(experience)
                    .employment(employment)
                    .schedule(schedule)
                    .skills(new ArrayList<>(skills))
                    .requirements(requirements)
                    .responsibilities(responsibilities)
                    .description(description)
                    .publishedAt(null)
                    .collectedAt(OffsetDateTime.now())
                    .build();

        } catch (Exception e) {
            log.warn("Не удалось распарсить карточку hh.ru: {}", e.getMessage());
            return null;
        }
    }

    private VacancyDetails loadVacancyDetails(String vacancyUrl) {
        try {
            Document doc = loadDocument(vacancyUrl);

            String description = firstText(
                    doc,
                    "[data-qa='vacancy-description']",
                    ".vacancy-description",
                    "[class*=vacancy-description]"
            );

            String salaryText = firstText(
                    doc,
                    "[data-qa='vacancy-salary']",
                    "[data-qa='vacancy-salary-compensation-type-net']",
                    "[data-qa='vacancy-salary-compensation-type-gross']",
                    "[class*=salary]"
            );

            String location = firstText(
                    doc,
                    "[data-qa='vacancy-view-location']",
                    "[data-qa='vacancy-view-raw-address']",
                    "[data-qa='vacancy-view-address']"
            );

            String experience = firstText(
                    doc,
                    "[data-qa='vacancy-experience']"
            );

            String employment = firstText(
                    doc,
                    "[data-qa='vacancy-view-employment-mode']",
                    "[data-qa='vacancy-view-employment']"
            );

            String schedule = firstText(
                    doc,
                    "[data-qa='vacancy-view-schedule']"
            );

            Set<String> skills = extractSkills(doc);

            String fullText = cleanText(doc.text());

            if (isBlank(experience)) {
                experience = extractExperience(fullText);
            }

            WorkFormat workFormat = extractWorkFormat(fullText);

            if (isBlank(employment)) {
                employment = extractEmployment(fullText);
            }

            if (isBlank(schedule)) {
                schedule = extractSchedule(fullText);
            }

            String requirements = extractRequirements(description);
            String responsibilities = extractResponsibilities(description);

            return new VacancyDetails(
                    description,
                    requirements,
                    responsibilities,
                    salaryText,
                    location,
                    experience,
                    employment,
                    schedule,
                    workFormat,
                    new ArrayList<>(skills)
            );

        } catch (Exception e) {
            log.warn("Не удалось загрузить детальную страницу hh.ru {}: {}", vacancyUrl, e.getMessage());
            return VacancyDetails.empty();
        }
    }

    private Document loadDocument(String url) throws Exception {
        Connection.Response response = Jsoup.connect(url)
                .userAgent(BROWSER_USER_AGENT)
                .referrer("https://www.google.com")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                .timeout(TIMEOUT_MS)
                .ignoreHttpErrors(true)
                .followRedirects(true)
                .execute();

        int statusCode = response.statusCode();
        String body = response.body();

        log.info("hh.ru response status: {}", statusCode);
        log.info("hh.ru final url: {}", response.url());
        log.info("hh.ru body length: {}", body != null ? body.length() : 0);

        Document doc = response.parse();

        log.info("hh.ru page title: {}", doc.title());
        log.info("hh.ru vacancy links count: {}", doc.select("a[href*='/vacancy/']").size());
        log.info("hh.ru cards count: {}", doc.select("[data-qa='vacancy-serp__vacancy'], .vacancy-serp-item, .serp-item").size());

        String pageText = doc.text().toLowerCase();

        if (pageText.contains("captcha")
                || pageText.contains("не робот")
                || pageText.contains("подтвердите")
                || pageText.contains("доступ ограничен")) {
            log.warn("hh.ru вернул страницу проверки или ограничения доступа");
        }

        if (statusCode == 403) {
            throw new IllegalStateException("hh.ru вернул HTTP 403");
        }

        if (statusCode >= 400) {
            throw new IllegalStateException("HTTP " + statusCode + " при запросе " + url);
        }

        return doc;
    }

    private Set<String> extractSkills(Element root) {
        Set<String> skills = new LinkedHashSet<>();

        Elements skillElements = root.select(
                "[data-qa='bloko-tag__text'], "
                        + "[data-qa='skills-element'], "
                        + ".bloko-tag__section_text, "
                        + "[class*=skill] span, "
                        + "[class*=tag] span"
        );

        for (Element element : skillElements) {
            String skill = cleanText(element.text());

            if (!skill.isBlank()
                    && skill.length() <= 50
                    && !skill.equalsIgnoreCase("Откликнуться")) {
                skills.add(skill);
            }
        }

        return skills;
    }

    private String extractExperience(String text) {
        if (isBlank(text)) {
            return "";
        }

        String lower = text.toLowerCase();

        if (lower.contains("нет опыта")) {
            return "Нет опыта";
        }

        if (lower.contains("от 1 года до 3 лет") || lower.contains("1–3 года") || lower.contains("1-3 года")) {
            return "1–3 года";
        }

        if (lower.contains("от 3 до 6 лет") || lower.contains("3–6 лет") || lower.contains("3-6 лет")) {
            return "3–6 лет";
        }

        if (lower.contains("более 6 лет")) {
            return "Более 6 лет";
        }

        if (lower.contains("junior")) {
            return "Junior";
        }

        if (lower.contains("middle")) {
            return "Middle";
        }

        if (lower.contains("senior")) {
            return "Senior";
        }

        if (lower.contains("lead")) {
            return "Lead";
        }

        return "";
    }

    private WorkFormat extractWorkFormat(String text) {
        if (isBlank(text)) {
            return WorkFormat.UNKNOWN;
        }

        String lower = text.toLowerCase();

        if (lower.contains("удаленная работа")
                || lower.contains("удалённая работа")
                || lower.contains("удаленно")
                || lower.contains("удалённо")
                || lower.contains("remote")) {
            return WorkFormat.REMOTE;
        }

        if (lower.contains("гибрид")) {
            return WorkFormat.HYBRID;
        }

        if (lower.contains("офис")) {
            return WorkFormat.OFFICE;
        }

        if (lower.contains("гибкий график")) {
            return WorkFormat.FLEXIBLE;
        }

        return WorkFormat.UNKNOWN;
    }

    private String extractEmployment(String text) {
        if (isBlank(text)) {
            return "";
        }

        String lower = text.toLowerCase();

        if (lower.contains("полная занятость")) {
            return "Полная занятость";
        }

        if (lower.contains("частичная занятость")) {
            return "Частичная занятость";
        }

        if (lower.contains("проектная работа")) {
            return "Проектная работа";
        }

        if (lower.contains("стажировка")) {
            return "Стажировка";
        }

        return "";
    }

    private String extractSchedule(String text) {
        if (isBlank(text)) {
            return "";
        }

        String lower = text.toLowerCase();

        if (lower.contains("полный день")) {
            return "Полный день";
        }

        if (lower.contains("гибкий график")) {
            return "Гибкий график";
        }

        if (lower.contains("сменный график")) {
            return "Сменный график";
        }

        if (lower.contains("удаленная работа") || lower.contains("удалённая работа")) {
            return "Удалённая работа";
        }

        return "";
    }

    private String extractRequirements(String description) {
        if (isBlank(description)) {
            return "";
        }

        String lower = description.toLowerCase();

        int start = indexOfAny(lower, List.of("требования", "мы ожидаем", "ожидаем от вас", "что нужно"));
        int end = indexOfAnyAfter(lower, List.of("обязанности", "задачи", "условия", "мы предлагаем"), start + 1);

        if (start >= 0 && end > start) {
            return cleanText(description.substring(start, end));
        }

        return "";
    }

    private String extractResponsibilities(String description) {
        if (isBlank(description)) {
            return "";
        }

        String lower = description.toLowerCase();

        int start = indexOfAny(lower, List.of("обязанности", "задачи", "чем предстоит заниматься"));
        int end = indexOfAnyAfter(lower, List.of("требования", "условия", "мы предлагаем"), start + 1);

        if (start >= 0 && end > start) {
            return cleanText(description.substring(start, end));
        }

        return "";
    }

    private int indexOfAny(String text, List<String> markers) {
        for (String marker : markers) {
            int index = text.indexOf(marker);

            if (index >= 0) {
                return index;
            }
        }

        return -1;
    }

    private int indexOfAnyAfter(String text, List<String> markers, int fromIndex) {
        if (fromIndex < 0) {
            return -1;
        }

        int result = -1;

        for (String marker : markers) {
            int index = text.indexOf(marker, fromIndex);

            if (index >= 0 && (result == -1 || index < result)) {
                result = index;
            }
        }

        return result;
    }

    private SalaryInfo parseSalary(String salaryText) {
        if (isBlank(salaryText)) {
            return new SalaryInfo(null, null, null, null);
        }

        String normalized = cleanText(salaryText);
        String lower = normalized.toLowerCase();

        String currency = detectCurrency(normalized);
        Boolean gross = detectGross(lower);

        List<BigDecimal> numbers = extractMoneyNumbers(normalized);

        if (numbers.isEmpty()) {
            return new SalaryInfo(null, null, currency, gross);
        }

        BigDecimal from = null;
        BigDecimal to = null;

        if (numbers.size() >= 2) {
            from = numbers.get(0);
            to = numbers.get(1);
        } else {
            BigDecimal value = numbers.get(0);

            if (lower.contains("до ")) {
                to = value;
            } else {
                from = value;
            }
        }

        return new SalaryInfo(from, to, currency, gross);
    }

    private List<BigDecimal> extractMoneyNumbers(String text) {
        List<BigDecimal> result = new ArrayList<>();

        Matcher matcher = Pattern.compile("(\\d[\\d\\s]{1,})").matcher(text);

        while (matcher.find()) {
            String raw = matcher.group(1).replaceAll("\\s+", "");

            if (!raw.isBlank()) {
                result.add(new BigDecimal(raw));
            }
        }

        return result;
    }

    private String detectCurrency(String text) {
        String lower = text.toLowerCase();

        if (lower.contains("₽") || lower.contains("руб")) {
            return "RUR";
        }

        if (lower.contains("$") || lower.contains("usd")) {
            return "USD";
        }

        if (lower.contains("€") || lower.contains("eur")) {
            return "EUR";
        }

        return null;
    }

    private Boolean detectGross(String lowerText) {
        if (lowerText.contains("до вычета") || lowerText.contains("gross")) {
            return true;
        }

        if (lowerText.contains("на руки") || lowerText.contains("net")) {
            return false;
        }

        return null;
    }

    private String buildShortDescription(String requirements, String responsibilities, String fallback) {
        StringBuilder builder = new StringBuilder();

        appendIfNotBlank(builder, "Требования", requirements);
        appendIfNotBlank(builder, "Обязанности", responsibilities);

        if (builder.isEmpty()) {
            return fallback;
        }

        return builder.toString().trim();
    }

    private void appendIfNotBlank(StringBuilder builder, String label, String value) {
        if (!isBlank(value)) {
            builder.append(label)
                    .append(": ")
                    .append(cleanText(value))
                    .append("\n");
        }
    }

    private String extractExternalId(String url) {
        if (isBlank(url)) {
            return null;
        }

        Matcher matcher = Pattern.compile("/vacancy/(\\d+)").matcher(url);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return url;
    }

    private Element firstElement(Element root, String... selectors) {
        for (String selector : selectors) {
            Element element = root.selectFirst(selector);

            if (element != null) {
                return element;
            }
        }

        return null;
    }

    private String firstText(Element root, String... selectors) {
        Element element = firstElement(root, selectors);

        if (element == null) {
            return "";
        }

        return cleanText(element.text());
    }

    private String textOrDefault(Element element, String defaultValue) {
        if (element == null) {
            return defaultValue;
        }

        String text = cleanText(element.text());

        return text.isBlank() ? defaultValue : text;
    }

    private String absoluteUrl(String href) {
        if (isBlank(href)) {
            return "";
        }

        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }

        if (href.startsWith("/")) {
            return BASE_URL + href;
        }

        return BASE_URL + "/" + href;
    }

    private String cleanText(String text) {
        if (text == null) {
            return "";
        }

        return text
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void politeDelay() {
        try {
            Thread.sleep(450L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record SalaryInfo(
            BigDecimal from,
            BigDecimal to,
            String currency,
            Boolean gross
    ) {
    }

    private record VacancyDetails(
            String description,
            String requirements,
            String responsibilities,
            String salaryText,
            String location,
            String experience,
            String employment,
            String schedule,
            WorkFormat workFormat,
            List<String> skills
    ) {
        static VacancyDetails empty() {
            return new VacancyDetails(
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    WorkFormat.UNKNOWN,
                    List.of()
            );
        }
    }
}