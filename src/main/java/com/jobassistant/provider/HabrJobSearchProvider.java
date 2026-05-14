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
public class HabrJobSearchProvider implements JobSearchProvider {

    private static final String BASE_URL = "https://career.habr.com";

    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";

    private static final int TIMEOUT_MS = 15_000;

    private static final int MAX_PAGES = 1;

    private static final boolean LOAD_DETAILS = true;

    @Override
    public String getProviderName() {
        return "HabrCareer";
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

            log.warn("Habr Career вернул HTTP {}", statusCode);
            return false;

        } catch (Exception e) {
            log.warn("Habr Career недоступен: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public List<JobSearchResult> search(JobSearchRequest request) {
        List<JobSearchResult> results = new ArrayList<>();

        if (request == null || isBlank(request.query())) {
            log.warn("Пустой поисковый запрос для Habr Career");
            return results;
        }

        String encodedQuery = URLEncoder.encode(request.query(), StandardCharsets.UTF_8);

        for (int page = 1; page <= MAX_PAGES; page++) {
            try {
                String url = BASE_URL + "/vacancies?q=" + encodedQuery + "&type=all&page=" + page;

                Document doc = loadDocument(url);

                Elements cards = doc.select(".vacancy-card");

                if (cards.isEmpty()) {
                    cards = doc.select("[class*=vacancy-card]");
                }

                log.info("Habr Career: страница {}, найдено карточек: {}", page, cards.size());

                for (Element card : cards) {
                    JobSearchResult result = parseCard(card);

                    if (result != null) {
                        results.add(result);
                    }

                    politeDelay();
                }

            } catch (Exception e) {
                log.error("Ошибка парсинга Habr Career, страница {}: {}", page, e.getMessage(), e);
            }
        }

        log.info("Habr Career: всего найдено {} вакансий", results.size());
        return results;
    }

    private JobSearchResult parseCard(Element card) {
        try {
            Element titleElement = firstElement(
                    card,
                    ".vacancy-card__title a",
                    "a[href*='/vacancies/']"
            );

            String title = textOrDefault(titleElement, "Без названия");
            String link = titleElement != null ? absoluteUrl(titleElement.attr("href")) : "";
            String externalId = extractExternalId(link);

            Element companyElement = firstElement(
                    card,
                    ".vacancy-card__company-title a",
                    "a[href*='/companies/']"
            );

            String company = textOrDefault(companyElement, "Не указана");

            String salaryText = firstText(
                    card,
                    ".basic-salary",
                    "[class*=salary]"
            );

            Set<String> skills = extractSkills(card);

            String cardText = cleanText(card.text());

            String experience = extractExperience(cardText);
            WorkFormat workFormat = extractWorkFormat(cardText);
            String location = extractLocation(cardText);

            VacancyDetails details = VacancyDetails.empty();

            if (LOAD_DETAILS && !isBlank(link)) {
                details = loadVacancyDetails(link);

                if (isBlank(salaryText)) {
                    salaryText = details.salaryText();
                }

                if (isBlank(experience)) {
                    experience = details.experience();
                }

                if (workFormat == WorkFormat.UNKNOWN) {
                    workFormat = details.workFormat();
                }

                if (isBlank(location)) {
                    location = details.location();
                }

                skills.addAll(details.skills());
            }

            SalaryInfo salaryInfo = parseSalary(salaryText);

            String description = !isBlank(details.description())
                    ? details.description()
                    : cardText;

            return JobSearchResult.builder()
                    .externalId(externalId)
                    .source("Habr Career")
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
                    .employment("")
                    .schedule("")
                    .skills(new ArrayList<>(skills))
                    .requirements(details.requirements())
                    .responsibilities(details.responsibilities())
                    .description(description)
                    .publishedAt(null)
                    .collectedAt(OffsetDateTime.now())
                    .build();

        } catch (Exception e) {
            log.warn("Не удалось распарсить карточку Habr Career: {}", e.getMessage());
            return null;
        }
    }

    private VacancyDetails loadVacancyDetails(String vacancyUrl) {
        try {
            Document doc = loadDocument(vacancyUrl);

            String salaryText = firstText(
                    doc,
                    ".basic-salary",
                    "[class*=salary]"
            );

            String description = firstText(
                    doc,
                    ".vacancy-description__text",
                    ".vacancy-description",
                    ".job-description"
            );

            if (isBlank(description)) {
                Element main = doc.selectFirst("main");
                description = main != null ? cleanText(main.text()) : "";
            }

            Set<String> skills = extractSkills(doc);

            String fullText = cleanText(doc.text());

            String experience = extractExperience(fullText);
            WorkFormat workFormat = extractWorkFormat(fullText);
            String location = extractLocation(fullText);

            String requirements = extractRequirements(description);
            String responsibilities = extractResponsibilities(description);

            return new VacancyDetails(
                    description,
                    requirements,
                    responsibilities,
                    salaryText,
                    location,
                    experience,
                    workFormat,
                    new ArrayList<>(skills)
            );

        } catch (Exception e) {
            log.warn("Не удалось загрузить детальную страницу Habr Career {}: {}", vacancyUrl, e.getMessage());
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

        if (statusCode >= 400) {
            throw new IllegalStateException("HTTP " + statusCode + " при запросе " + url);
        }

        return response.parse();
    }

    private Set<String> extractSkills(Element root) {
        Set<String> skills = new LinkedHashSet<>();

        Elements skillElements = root.select(
                ".vacancy-card__skills a, "
                        + ".vacancy-card__skills span, "
                        + "a[href*='/vacancies/skills/'], "
                        + "[class*=skill] a, "
                        + "[class*=skill] span, "
                        + "[class*=tag] a, "
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

        if (lower.contains("intern") || lower.contains("стажер") || lower.contains("стажёр")) {
            return "Intern";
        }

        if (lower.contains("junior") || lower.contains("джуниор")) {
            return "Junior";
        }

        if (lower.contains("middle") || lower.contains("мидл")) {
            return "Middle";
        }

        if (lower.contains("senior") || lower.contains("сеньор")) {
            return "Senior";
        }

        if (lower.contains("lead") || lower.contains("тимлид") || lower.contains("team lead")) {
            return "Lead";
        }

        return "";
    }

    private WorkFormat extractWorkFormat(String text) {
        if (isBlank(text)) {
            return WorkFormat.UNKNOWN;
        }

        String lower = text.toLowerCase();

        if (lower.contains("можно удалённо")
                || lower.contains("можно удаленно")
                || lower.contains("удалённо")
                || lower.contains("удаленно")
                || lower.contains("удаленная")
                || lower.contains("удалённая")
                || lower.contains("remote")) {
            return WorkFormat.REMOTE;
        }

        if (lower.contains("гибрид")) {
            return WorkFormat.HYBRID;
        }

        if (lower.contains("офис")) {
            return WorkFormat.OFFICE;
        }

        if (lower.contains("гибкий")) {
            return WorkFormat.FLEXIBLE;
        }

        return WorkFormat.UNKNOWN;
    }

    private String extractLocation(String text) {
        if (isBlank(text)) {
            return "";
        }

        List<String> knownCities = List.of(
                "Москва",
                "Санкт-Петербург",
                "Новосибирск",
                "Казань",
                "Нижний Новгород",
                "Екатеринбург",
                "Краснодар",
                "Воронеж",
                "Самара",
                "Ростов-на-Дону"
        );

        for (String city : knownCities) {
            if (text.contains(city)) {
                return city;
            }
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

    private String extractExternalId(String url) {
        if (isBlank(url)) {
            return null;
        }

        Matcher matcher = Pattern.compile("/vacancies/(\\d+)").matcher(url);

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
            Thread.sleep(350L);
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
                    WorkFormat.UNKNOWN,
                    List.of()
            );
        }
    }
}