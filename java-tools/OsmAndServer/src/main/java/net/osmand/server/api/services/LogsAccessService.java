package net.osmand.server.api.services;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;

import nl.basjes.parse.core.Field;
import nl.basjes.parse.core.Parser;
import nl.basjes.parse.core.Parser.SetterPolicy;
import nl.basjes.parse.httpdlog.HttpdLoglineParser;
import nl.basjes.parse.httpdlog.dissectors.TimeStampDissector;

@Service

public class LogsAccessService {
    protected static final Log LOGGER = LogFactory.getLog(LogsAccessService.class);
    private static final String APACHE_LOG_FORMAT = "%h %l %u %t \"%r\" %>s %O \"%{Referer}i\" \"%{User-Agent}i\"";
    private static final String DEFAULT_LOG_LOCATION = "/var/log/nginx/";
    
    Gson gson = new Gson();
    
    @Autowired
	private IpLocationService locationService;
	
    
    public enum LogsPresentation {
    	PLAIN,
    	BEHAVIOR,
    	STATS
    }
    
    public static class LogParsingResult {
    	StopWatch readTime = new StopWatch();
		StopWatch parseTime = new StopWatch();
		Date beginDate = null;
		Date endDate = null;
		long totalRows;
		int rows = 0;
		int err = 0;
		OutputStream out;
		Map<String, UserAccount> behaviorMap = new LinkedHashMap<String, UserAccount>();
		Map<String, Stat> stats = new LinkedHashMap<String, Stat>();
    }
    
	public void parseLogs(Date startTime, Date endTime, boolean parseRegion, long limit, String uriFilter, String logFilter,
			LogsPresentation presentation, 
			OutputStream out) throws IOException {
		gson = new GsonBuilder().setPrettyPrinting().create();
		LogParsingResult r = new LogParsingResult();
		r.out = out;
		if (presentation == LogsPresentation.BEHAVIOR && limit < 0) {
			limit = 1000000l;
		}
		if (presentation == LogsPresentation.PLAIN) {
			r.out.write((LogEntry.toCSVHeader() + "\n").getBytes());
			r.out.flush();
		}
		File logFile = new File(DEFAULT_LOG_LOCATION, "access.log.1");
		if (logFile.exists()) {
			readLogFile(logFile, startTime, endTime, parseRegion, limit, uriFilter, logFilter, presentation, r);
		}
		if (r.totalRows < limit || limit < 0) {
			logFile = new File(DEFAULT_LOG_LOCATION, "access.log");
			readLogFile(logFile, startTime, endTime, parseRegion, limit, uriFilter, logFilter, presentation, r);
		}
		
		
		if (presentation != LogsPresentation.PLAIN) {
			out.write(String
					.format("{\"errors\" : %d, \"rows\" : %d, \"parseTime\" : %d, \"readTime\" : %d, "
							+ "\"begin\":\"%5$tF %5$tT\", \"end\":\"%6$tF %6$tT\", ", r.err, r.rows,
							r.parseTime.getTotalTimeMillis(), r.readTime.getTotalTimeMillis(), r.beginDate, r.endDate).getBytes());
		}
		if (presentation == LogsPresentation.BEHAVIOR) {
			out.write("\n\"accounts\" : [".getBytes());
			Iterator<Entry<String, UserAccount>> i = r.behaviorMap.entrySet().iterator();
			boolean f = true;
			while (i.hasNext()) {
				Entry<String, UserAccount> nxt = i.next();
				UserAccount v = nxt.getValue();
				if (!nxt.getKey().equals(v.aid)) {
					continue;
				}
				if (!f) {
					out.write(",\n".getBytes());
				} else {
					f = false;
				}
				int duration = (int) ((v.maxdate - v.mindate) / (60 * 1000l));
				v.duration = String.format("%02d:%02d", duration / 60, duration % 60);
				out.write(gson.toJson(v).getBytes());
			}

			out.write("]}".getBytes());
		} else if (presentation == LogsPresentation.STATS) {
			out.write("\n\"stats\" : ".getBytes());
			List<Stat> sortStats = new ArrayList<Stat>(r.stats.values());
			r.stats.clear();
			Collections.sort(sortStats, new Comparator<Stat>() {

				@Override
				public int compare(Stat o1, Stat o2) {
					return -Integer.compare(o1.uniqueCount, o2.uniqueCount);
				}

			});
			for (Stat s : sortStats) {
				s.calculateFollowUps(sortStats);
				r.stats.put(s.uri, s);
			}

			out.write(gson.toJson(r.stats).getBytes());
			out.write("}".getBytes());
		}
		out.close();

	}

	private void readLogFile(File logFile, Date startTime, Date endTime, boolean parseRegion, long limit, String uriFilter,
			String logFilter, LogsPresentation presentation, LogParsingResult r)
			throws FileNotFoundException, IOException {
		RandomAccessFile raf = new RandomAccessFile(logFile, "r");
		BufferedReader bufferedReader = null;
		Parser<LogEntry> parser = new HttpdLoglineParser<>(LogEntry.class, APACHE_LOG_FORMAT);
		try {
			Pattern aidPattern = Pattern.compile("aid=([a-z,0-9]*)");
			LogEntry l = new LogEntry();
			long currentLimit = raf.length();
			boolean found = true;
			if(startTime != null) {
				found = seekStartTime(parser, startTime, raf);
			}
			
			
			bufferedReader = new BufferedReader(new FileReader(raf.getFD()));
			while (found) {
				r.readTime.start();
				String ln = bufferedReader.readLine();
				r.readTime.stop();
				if (ln == null) {
					break;
				}
				if (raf.getFilePointer() > currentLimit) {
					break;
				}
				r.totalRows++;
				if (r.totalRows >= limit && limit != -1) {
					break;
				}
				if (logFilter != null && logFilter.length() > 0 && !ln.contains(logFilter)) {
					// quick filter is not correct for behavior
					continue;
				}
				l.clear();
				try {
					r.parseTime.start();
					parser.parse(l, ln);
					r.parseTime.stop();
				} catch (Exception e) {
					if (r.err++ % 100 == 0) {
						if(presentation == LogsPresentation.PLAIN) {
							r.out.write(String.format("Error parsing %d\n", r.err).getBytes());
						}
					}
					continue;
				}
				if (startTime != null && startTime.getTime() > l.date.getTime()) {
					// don't count row to limit
					r.totalRows--;
					continue;
				}
				if (endTime != null && endTime.getTime() < l.date.getTime()) {
					break;
				}
				if (l.date == null) {
					// don't count row to limit
					r.totalRows--;
					continue;
				}
				if (r.beginDate == null) {
					r.beginDate = l.date;
				}
				r.endDate = l.date;
				
				Matcher aidMatcher = aidPattern.matcher(l.uri);
				String aid = aidMatcher.find() ? aidMatcher.group(1) : null ;
				if (uriFilter != null && uriFilter.length() > 0) {
					if (!l.uri.contains(uriFilter) && !r.behaviorMap.containsKey(l.ip) && !r.behaviorMap.containsKey(aid)) {
						continue;
					}
				}
				r.rows++;
				UserAccount accountAid = presentation == LogsPresentation.BEHAVIOR ? retrieveUniqueAccount(aid, l,
						r.behaviorMap) : null;
				if(parseRegion) {
					l.region = locationService.getField(l.ip, IpLocationService.COUNTRY_NAME);
					if(accountAid != null) {
						accountAid.regions.add(l.region);
					}
				}
				if(presentation == LogsPresentation.BEHAVIOR) {
					if(l.status.startsWith("4")) {
						continue;
					}
					accountAid.add(l);
				} else if(presentation == LogsPresentation.STATS) {
					if(l.status.startsWith("4")) {
						continue;
					}
					String uri = l.uri;
					int i = uri.indexOf('?');
					if(i > 0) {
						uri = uri.substring(0, i);
					}
					if(!uri.startsWith("/api") && !uri.startsWith("/subscription")) {
						i = uri.indexOf('/', 1);
						if(i > 0) {
							uri = uri.substring(0, i);
						}
					}
					Stat stat = r.stats.get(uri);
					if(stat == null) {
						stat = new Stat();
						stat.uri = uri;
						r.stats.put(uri, stat);
					}
					stat.add(aid, l);
				} else {
					r.out.write((l.toCSVString() + "\n").getBytes());
				}
				
				if(r.rows % 1000 == 0) {
					r.out.flush();
				}
			}
		} finally {
			if (bufferedReader != null) {
				bufferedReader.close();
			}
			raf.close();
		}
	}
	
	private UserAccount retrieveUniqueAccount(String aid, LogEntry l, Map<String, UserAccount> behaviorMap) {
		UserAccount accountAid = behaviorMap.get(aid);
		String ip =  l.ip;
		if(l.ip.equals("127.0.0.1")) {
			ip = "127." + l.date.getTime();
		}
		UserAccount accountIp =  behaviorMap.get(ip);
		if(aid == null) {
			if(accountIp == null) {
				accountIp = new UserAccount(aid, ip, l.date);
				behaviorMap.put(ip, accountIp);
			}
			accountAid = accountIp;
		} else {
			if(accountAid == null) {
				accountAid = new UserAccount(aid, ip, l.date);
				behaviorMap.put(aid, accountAid);
			}
			if(accountIp == null) {
				accountIp = accountAid; 
				behaviorMap.put(ip, accountIp);
			} else if(accountIp != accountAid){
				accountIp = accountAid.merge(accountIp);
				behaviorMap.put(ip, accountIp);
			}
		}
		return accountAid;
	}
	
	private boolean seekStartTime(Parser<LogEntry> parser, Date startTime, RandomAccessFile raf)
			throws IOException {
		long currentLimit = raf.length();
		long pos = 0;
		boolean found = false;
		// seek position
		long step = 1 << 24; // 16 MB
		LogEntry lt = new LogEntry();

		while(!found) {
			if(currentLimit > pos + step) {
				raf.seek(pos + step);
				// skip incomplete line
				raf.readLine();
				try {
					parser.parse(lt, raf.readLine());
					if(startTime.getTime() < lt.date.getTime()) {
						raf.seek(pos);
						// skip incomplete line
						raf.readLine();
						found = true;
						break;
					}
				} catch (Exception e) {
				}
				pos += step;
			} else {
				break;
			}
		}
		return found;
	}
	
	
	protected static class UserAction {
		String ip;
		long time;
		String uri;
		String timeFormat;
		String region;
		Map<String, String> params = new TreeMap<String, String>();
		public UserAction(LogEntry l) {
			time = l.date.getTime();
			this.region = l.region;
			timeFormat = String.format("%1$tF %1$tR", l.date);
			ip = l.ip;
			uri = l.uri;
			int i = uri.indexOf('?');
			if(i > 0) {
				uri = uri.substring(0, i);
				String[] ls = l.uri.substring(i+1).split("&");
				for (String lt : ls) {
					String[] ks = lt.split("=");
					if (ks.length > 1) {
						params.put(ks[0], ks[1]);
					} else if (ks.length > 0) {
						params.put(ks[0], "");
					}
				}
			}
		}
		
	}
	

	protected static class UserAccount {
		String aid;
		Set<String> ips = new LinkedHashSet<String>();
		Set<String> regions = new LinkedHashSet<String>();
		long mindate;
		long maxdate;
		String duration = "";
		List<UserAction> actions = new ArrayList<UserAction>();
		
		public UserAccount(String aid, String ip, Date date) {
			this.aid = aid == null ? ip : aid;
			ips.add(ip);
			mindate = maxdate = date.getTime();
		}
		
		public UserAccount merge(UserAccount accountIp) {
			ips.addAll(accountIp.ips);
			regions.addAll(accountIp.regions);
			actions.addAll(accountIp.actions);
			this.mindate = Math.min(accountIp.mindate, this.mindate);
			this.maxdate = Math.max(accountIp.maxdate, this.maxdate);
			Collections.sort(actions, new Comparator<UserAction>() {

				@Override
				public int compare(UserAction o1, UserAction o2) {
					return Long.compare(o1.time, o2.time);
				}
				
			});
			return this;
		}

		public void add(LogEntry l) {
			this.mindate = Math.min(l.date.getTime(), this.mindate);
			this.maxdate = Math.max(l.date.getTime(), this.maxdate);
			actions.add(new UserAction(l));
		}
		
		
	}
	
	protected static class Stat {
		String uri;
		int count;
		int uniqueCount;
		int session = 0;
		
		@Expose(serialize = false)
		transient Map<String, Long> aids = new TreeMap<String, Long>();
		@Expose(serialize = false)
		transient Map<String, Long> ips = new TreeMap<String, Long>();
		
		Map<String, Integer> followUps = new LinkedHashMap<String, Integer>();
		Map<String, Integer> followUpTimes = new LinkedHashMap<String, Integer>();
		
		public void calculateFollowUps(Collection<Stat> stats) {
			DescriptiveStatistics session = new DescriptiveStatistics();
			for(String ip : ips.keySet()) {
				Long l1 = ips.get(ip);
				for(Stat m : stats) {
					Long l2 = m.ips.get(ip);
					if(l2 != null && l2.longValue() > l1.longValue()) {
						session.addValue(l2.longValue() / 1000 - l1.longValue() / 1000);
					}
				}
			}
			this.session = (int) session.getPercentile(50);
			for(Stat m : stats) {
				DescriptiveStatistics delay = new DescriptiveStatistics();
				int count = 0;
				if(m == this) {
					continue;
				}
				Map<String, Long> thisit = ips;
				Map<String, Long> thatit = m.ips;
				if(m.aids.size() > 0 && aids.size() > 0) {
					thisit = aids;
					thatit = m.aids;
				}
				Iterator<Entry<String, Long>> vl = thisit.entrySet().iterator();
				while(vl.hasNext()) {
					Entry<String, Long> e = vl.next();
					Long tm2 = thatit.get(e.getKey());
					
					if (tm2 != null && e.getValue().longValue() < tm2.longValue()) {
						count++;
						delay.addValue(tm2.longValue() / 1000 - e.getValue().longValue() / 1000);
					}
				}
				if(count > 0 && 100 * count > uniqueCount ) {
					followUps.put(m.uri, count);
					followUpTimes.put(m.uri, (int) delay.getPercentile(50));
				}
			}
			List<String> sortedList = new ArrayList<String>(followUps.keySet());
			Collections.sort(sortedList, new Comparator<String>() {

				@Override
				public int compare(String o1, String o2) {
					return -Integer.compare(followUps.get(o1), followUps.get(o2));
				}
			});
			for(String k : sortedList) {
				Integer ct = followUps.remove(k);
				followUps.put(k, ct * 1000 / uniqueCount);
			}

			
			
		}
		
		public void add(String aid, LogEntry l) {
			if(aid != null) {
				aids.putIfAbsent(aid, l.date.getTime());
			}
			ips.putIfAbsent(l.ip, l.date.getTime());
			count++;
			uniqueCount = getUniqueCount();
		}
		
		public int getUniqueCount() {
		    return (aids.size() == 0 ? ips.size() : aids.size()) ;
		}
		
	}
	
	public static class LogEntry {
		public String ip;
		public Date date;
		public String uri;
		private String userAgent;
		private String status;
		private String referrer;
		private String region;
		private static final SimpleDateFormat format = new SimpleDateFormat(TimeStampDissector.DEFAULT_APACHE_DATE_TIME_PATTERN);
		
	    @Field("IP:connection.client.host")
	    public void setIP(final String value) {
	        ip =  value;
	    }
	    
	    @Field("TIME.STAMP:request.receive.time")
	    public void setTime(final String value) throws ParseException {
			date = format.parse(value);
	    }
	    
	    @Field("HTTP.URI:request.firstline.uri")
	    public void setURI(String value) {
	    	uri = value;
	    }
	    
	    @Field("HTTP.USERAGENT:request.user-agent")
	    public void setUserAgent(String value) {
	    	userAgent = value;
	    }
	    
	    @Field("STRING:request.status.last")
	    public void setStatusCode(String value) {
	    	status = value;
	    }
	    @Field(value = {"HTTP.URI:request.referer"}, setterPolicy = SetterPolicy.ALWAYS)
	    public void setReferrer(String value) {
	    	referrer = value == null  ? "" : value;
	    }
	    
	    public static String toCSVHeader() {
	    	// add nd, aid, np, ...
	    	return "IP,Region,Date,Time,Status,User-Agent,Referrer,Path,Version,Lang,Query,URL"; 
	    }
	    
	    public String toCSVString() {
	    	String path = "";
	    	String query = "";
	    	String version = "";
	    	String lang = "";
//	    	String nd = "";
//	    	String ns = "";
//	    	String aid = "";
	    	if(uri != null && uri.length() > 0) {
				try {
					URL l = new URL("http://127.0.0.1" + uri);
					if (l.getPath() != null) {
						path = l.getPath();
					}
					if (l.getQuery() != null) {
						query = l.getQuery();
						String[] params = query.split("&");
						for (String param : params) {
							int ind = param.indexOf('=');
							if(ind == -1) {
								continue;
							}
							String name = param.substring(0, ind);
							String value = param.substring(ind + 1);
							if ("version".equals(name)) {
								version = value;
							} else if ("lang".equals(name)) {
								lang = value;
							}
						}
					}
					uri = "";
					
				} catch (MalformedURLException e) {
				}
	    		
	    	}
	    	
			return String.format("%s,%s,%tF,%tT,%s,%s,%s,%s,%s,%s,%s,%s", 
					ip, region, date, date, status, userAgent == null ? "" : userAgent.replace(",", ";"), referrer, 
					path, version, lang, query, uri);
	    }
	    
	    public String toString() {
	        return String.format("Request %s %tF %2$tT %s (user-agent %s, referrer %s): %s", ip, date, status, userAgent, referrer, uri);
	    }

	    public void clear() {
	    	this.ip = "";
	    	this.date = null;
	    	this.referrer = "";
	    	this.region = "";
	    	this.userAgent = "";
	    	this.status = "";
	    	this.uri = "";
	    }
	}
}
