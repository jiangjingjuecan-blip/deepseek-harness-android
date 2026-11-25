package com.sunwin.deepseekharness;

import org.json.*;
import java.net.URI;
import java.util.*;

/** Native DeepSeek web search & URL reading settings, status formatting, and source persistence. */
final class WebTools {
    static final String PREF="web_search";
    static final boolean DEFAULT_ENABLED=true;
    static final String FLASH_MODEL="deepseek-flash";

    static final class Source {
        final String url, title, site;
        Source(String url,String title,String site) {
            this.url=url; this.title=title==null?"":title.trim(); this.site=site==null?"":site.trim();
        }
        String label() {
            if (!site.isEmpty()) return site;
            if (!title.isEmpty()) return title;
            String h=host(url);
            return h.isEmpty() ? url : h;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Source)) return false;
            Source s=(Source)o;
            return url.equals(s.url) && title.equals(s.title) && site.equals(s.site);
        }
        @Override public int hashCode() { return Objects.hash(url,title,site); }
    }

    static String effectiveModel(String selected,List<String> available,boolean webSearchOn) {
        if (webSearchOn && available!=null && available.contains(FLASH_MODEL)) return FLASH_MODEL;
        return selected==null ? "" : selected;
    }

    static String mode(String effectiveModel,boolean webSearchOn) {
        if (!FLASH_MODEL.equals(effectiveModel)) return "off";
        return webSearchOn ? "search" : "read";
    }

    static String safeHttpUrl(String raw) {
        if (raw==null) return null;
        String trimmed=raw.trim();
        if (trimmed.isEmpty()) return null;
        try {
            URI uri=URI.create(trimmed);
            String scheme=uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
            if (uri.getHost()==null || uri.getHost().isEmpty()) return null;
            return uri.toASCIIString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String host(String raw) {
        if (raw==null || raw.trim().isEmpty()) return "";
        try {
            String h=URI.create(raw.trim()).getHost();
            if (h==null) return "";
            if (h.startsWith("www.") && h.length()>4) return h.substring(4);
            return h;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    static String activityLabel(String action,String kind,List<String> queries,String url,String pattern) {
        if ("search".equals(action)) {
            if (queries!=null && !queries.isEmpty()) {
                List<String> nonEmpty=new ArrayList<>();
                for (String q:queries) if (q!=null && !q.trim().isEmpty()) nonEmpty.add(q.trim());
                if (!nonEmpty.isEmpty()) return "搜索 "+String.join("、",nonEmpty);
            }
            if (kind!=null && kind.contains("image")) return "识图搜索";
            return "正在搜索";
        }
        if ("read_link".equals(action) || "open_page".equals(action)) {
            String h=host(url);
            return h.isEmpty() ? "正在阅读网页" : "阅读 "+h;
        }
        if ("find_in_page".equals(action)) {
            String h=host(url);
            String p=pattern==null ? "" : pattern.trim();
            if (!h.isEmpty() && !p.isEmpty()) return "查找 "+h+" · "+p;
            return h.isEmpty() ? "查找网页内容" : "查找 "+h;
        }
        return "正在联网";
    }

    static String toolErrorLabel(String errorCode,String kind) {
        if (errorCode==null || errorCode.isEmpty()) return "";
        switch (errorCode) {
            case "url_not_accessible": return "网页无法访问";
            case "url_not_allowed": return "网页不允许访问";
            case "unavailable":
                boolean search=kind!=null && kind.contains("search");
                return search ? "联网搜索失败" : "网页暂不可用";
            default: return "联网出错";
        }
    }

    static List<Source> parseSources(String json) {
        if (json==null || json.trim().isEmpty()) return Collections.emptyList();
        try {
            JSONArray arr=new JSONArray(json);
            List<Source> out=new ArrayList<>(arr.length());
            Set<String> seen=new HashSet<>();
            for (int i=0;i<arr.length();i++) {
                JSONObject obj=arr.optJSONObject(i);
                if (obj==null) continue;
                String url=safeHttpUrl(obj.optString("url",""));
                if (url==null || !seen.add(url)) continue;
                out.add(new Source(url,obj.optString("title",""),obj.optString("site","")));
            }
            return out;
        } catch (JSONException e) {
            return Collections.emptyList();
        }
    }

    static String serializeSources(List<Source> sources) {
        if (sources==null || sources.isEmpty()) return null;
        JSONArray arr=new JSONArray();
        Set<String> seen=new HashSet<>();
        for (Source s:sources) {
            String url=safeHttpUrl(s.url);
            if (url==null || !seen.add(url)) continue;
            arr.put(Wire.obj("url",url,"title",s.title,"site",s.site));
        }
        return arr.length()==0 ? null : arr.toString();
    }
}
