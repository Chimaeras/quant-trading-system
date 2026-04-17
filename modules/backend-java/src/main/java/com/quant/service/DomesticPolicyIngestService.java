package com.quant.service;

import com.quant.entity.PolicyIndustryDaily;
import com.quant.entity.StockInfo;
import com.quant.repository.PolicyIndustryDailyRepository;
import com.quant.repository.StockInfoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 国内财经滚动新闻（新浪等）关键词 + 行业名匹配，写入 policy_industry_daily；与 GDELT 地缘因子独立。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomesticPolicyIngestService {

    private final StockInfoRepository stockInfoRepository;
    private final PolicyIndustryDailyRepository policyIndustryDailyRepository;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final List<String> GOOD = List.of(
            "补贴", "减税", "税收优惠", "支持", "利好", "扶持", "降准", "降息", "稳增长", "产业基金", "政策利好"
    );
    private static final List<String> BAD = List.of(
            "监管", "处罚", "立案", "调查", "限制", "禁令", "关停", "退市", "风险", "利空", "收紧"
    );

    private static final Pattern TITLE_A = Pattern.compile(
            "<a[^>]+href=[^>]+>([^<]{6,120})</a>", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    @Transactional
    public Map<String, Object> ingestForTradeDate(LocalDate tradeDate) {
        Set<String> industries = stockInfoRepository.findAll().stream()
                .map(StockInfo::getIndustry)
                .filter(s -> s != null && !s.isBlank() && s.length() >= 2)
                .collect(Collectors.toSet());

        List<String> titles = fetchFinanceTitles();
        Map<String, Double> acc = new HashMap<>();
        for (String title : titles) {
            int sent = sentimentScore(title);
            if (sent == 0) {
                continue;
            }
            for (String ind : industries) {
                if (title.contains(ind)) {
                    acc.merge(ind, (double) sent, Double::sum);
                }
            }
        }

        policyIndustryDailyRepository.deleteByTradeDate(tradeDate);
        List<PolicyIndustryDaily> rows = new ArrayList<>();
        for (Map.Entry<String, Double> e : acc.entrySet()) {
            double sc = Math.max(-10, Math.min(10, e.getValue()));
            PolicyIndustryDaily p = new PolicyIndustryDaily();
            p.setTradeDate(tradeDate);
            p.setIndustry(e.getKey());
            p.setScore(sc);
            p.setNote("domestic_news_keywords");
            rows.add(p);
        }
        policyIndustryDailyRepository.saveAll(rows);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trade_date", tradeDate.toString());
        out.put("titles_parsed", titles.size());
        out.put("industry_scores", rows.size());
        return out;
    }

    private static int sentimentScore(String title) {
        int s = 0;
        for (String g : GOOD) {
            if (title.contains(g)) {
                s += 2;
            }
        }
        for (String b : BAD) {
            if (title.contains(b)) {
                s -= 2;
            }
        }
        return s;
    }

    private List<String> fetchFinanceTitles() {
        List<String> out = new ArrayList<>();
        String[] urls = {
                "https://finance.sina.com.cn/roll/finance_1_index.htm",
                "https://finance.sina.com.cn/roll/index.d.html?cid=56592&page=1"
        };
        for (String url : urls) {
            try {
                HttpHeaders h = new HttpHeaders();
                h.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                String html = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(h), String.class).getBody();
                if (html == null) {
                    continue;
                }
                Matcher m = TITLE_A.matcher(html);
                while (m.find() && out.size() < 200) {
                    String t = m.group(1).trim();
                    if (t.length() > 8) {
                        out.add(t);
                    }
                }
                if (!out.isEmpty()) {
                    break;
                }
            } catch (Exception e) {
                log.debug("policy fetch {} failed: {}", url, e.getMessage());
            }
        }
        return out;
    }
}
