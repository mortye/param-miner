package burp;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.apache.commons.collections4.queue.CircularFifoQueue;
import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.*;

import static java.lang.Math.max;
import static java.lang.Math.min;


class SimpleScan implements Runnable, IExtensionStateListener {

    public void run() {

    }

    public void extensionUnloaded() {
        Utilities.log("Aborting param bruteforce");
        Utilities.unloaded.set(true);
    }

}
/**
 * Created by james on 30/08/2017.
 */
class ParamGuesser implements Runnable, IExtensionStateListener {

    private IHttpRequestResponse req;
    private boolean backend;
    private byte type;
    private ParamGrabber paramGrabber;// should be a power of 2 for max efficiency
    public static final int BUCKET_SIZE = 16;

    public ParamGuesser(IHttpRequestResponse req, boolean backend, byte type, ParamGrabber paramGrabber) {
        this.paramGrabber = paramGrabber;
        this.req = req;
        this.backend = backend;
        this.type = type;
    }

    public void run() {

        IRequestInfo info = Utilities.helpers.analyzeRequest(req);
        List<IParameter> params = info.getParameters();

        if (req.getResponse() == null) {
            Utilities.log("Baserequest has no response - fetching...");
            req = Utilities.callbacks.makeHttpRequest(req.getHttpService(), req.getRequest());
        }

        if(backend) {

            for (IParameter param : params) {
                String key = null;
                String[] keys = {"%26zq=%253c", "!zq=%253c"};
                for (String test : keys) {
                    if (param.getValue().contains(test)) {
                        key = test;
                        break;
                    }
                }

                if (key != null) {
                    String originalValue = param.getValue().substring(0, param.getValue().indexOf(key));
                    ParamInsertionPoint insertionPoint = new ParamInsertionPoint(req.getRequest(), param.getName(), originalValue, param.getType());
                    ArrayList<Attack> paramGuesses = guessBackendParams(req, insertionPoint);
                    if (!paramGuesses.isEmpty()) {
                        Utilities.callbacks.addScanIssue(Utilities.reportReflectionIssue(paramGuesses.toArray((new Attack[paramGuesses.size()])), req));
                    }
                    break;
                }

            }
        }
        else {
            ArrayList<Attack> paramGuesses = guessParams(req, type);
            if (!paramGuesses.isEmpty()) {
                Utilities.callbacks.addScanIssue(Utilities.reportReflectionIssue(paramGuesses.toArray((new Attack[paramGuesses.size()])), req));
            }
        }
    }

    public void extensionUnloaded() {
        Utilities.log("Aborting param bruteforce");
        Utilities.unloaded.set(true);
    }

    static ArrayList<String> calculatePayloads(IHttpRequestResponse baseRequestResponse, byte type, ParamGrabber paramGrabber) {
        ArrayList<String> params = new ArrayList<>();

        // collect keys in request, for key skipping, matching and re-mapping
        HashMap<String, String> requestParams = new HashMap<>();
        for (String entry: Keysmith.getAllKeys(baseRequestResponse.getRequest(), new HashMap<>())) { // todo give precedence to shallower keys
            String[] parsed = Keysmith.parseKey(entry);
            Utilities.log("Request param: " +parsed[1]);
            requestParams.putIfAbsent(parsed[1], parsed[0]);
        }

        // add JSON from response
        params.addAll(Keysmith.getAllKeys(baseRequestResponse.getResponse(), requestParams));

        // add JSON from method-flip response
        if(baseRequestResponse.getRequest()[0] != 'G') {
            IHttpRequestResponse getreq = Utilities.callbacks.makeHttpRequest(baseRequestResponse.getHttpService(),
                    Utilities.helpers.toggleRequestMethod(baseRequestResponse.getRequest()));
            params.addAll(Keysmith.getAllKeys(getreq.getResponse(), requestParams));
        }


        // add JSON from elsewhere
        HashMap<Integer, Set<String>> responses = new HashMap<>();

        Iterator<IHttpRequestResponse> savedJson = paramGrabber.getSavedJson().iterator();
        while (savedJson.hasNext()) {
            IHttpRequestResponse resp = null; // todo record resp
            try {
                resp = savedJson.next();
            }
            catch (NoSuchElementException e) {
                break;
            }

            JsonParser parser = new JsonParser();
            JsonElement json = parser.parse(Utilities.getBody(resp.getResponse()));
            HashSet<String> keys = new HashSet<>(Keysmith.getJsonKeys(json, requestParams));
            int matches = 0;
            for (String requestKey: keys) {
                if (requestParams.containsKey(requestKey) || requestParams.containsKey(Keysmith.parseKey(requestKey)[1])) {
                    matches++;
                }
            }

            // if there are no matches, don't bother with prefixes
            // todo use root (or non-leaf) objects only
            if(matches < 1) {
                //Utilities.out("No matches, discarding prefix");
                HashSet<String> filteredKeys = new HashSet<>();
                for(String key: keys) {
                    String lastKey = Keysmith.parseKey(key)[1];
                    if (Utilities.parseArrayIndex(lastKey) < 3) {
                        filteredKeys.add(Keysmith.parseKey(key)[1]);
                    }
                }
                keys = filteredKeys;
            }

            Integer matchKey = matches;
            if(responses.containsKey(matchKey)) {
                responses.get(matchKey).addAll(keys);
            }
            else {
                responses.put(matchKey, keys);
            }
        }


        final TreeSet<Integer> sorted = new TreeSet<>(Collections.reverseOrder());
        sorted.addAll(responses.keySet());
        for(Integer key: sorted) {
            Utilities.log("Loading keys with "+key+" matches");
            ArrayList<String> sortedByLength = new ArrayList<>(responses.get(key));
            sortedByLength.sort(new LengthCompare());
            params.addAll(sortedByLength);
        }

        if (params.size() > 0) {
            Utilities.log("Loaded " + new HashSet<>(params).size() + " params from response");
        }

        params.addAll(paramGrabber.getSavedGET());

        params.addAll(Utilities.paramNames);

        // only use keys if the request isn't JSON
        // todo accept two levels of keys if it's using []
        //if (type != IParameter.PARAM_JSON) {
        //    for(int i=0;i<params.size();i++) {
        //        params.set(i, Keysmith.parseKey(params.get(i))[1]);
        //    }
        //}

        // de-dupe without losing the ordering
        params = new ArrayList<>(new LinkedHashSet<>(params));

        // don't both using parameters that are already present
        Iterator<String> refiner = params.iterator();
        while (refiner.hasNext()) {
            String candidate = refiner.next();
            String finalKey = Keysmith.getKey(candidate);
            if (requestParams.containsKey(candidate) ||
                    requestParams.containsKey(finalKey) || requestParams.containsValue(candidate) || requestParams.containsValue(finalKey)) {
                refiner.remove();
            }

        }


        return params;
    }

    ArrayList<Attack> guessParams(IHttpRequestResponse baseRequestResponse, byte type) {
        ArrayList<Attack> attacks = new ArrayList<>();
        String targetURL = baseRequestResponse.getHttpService().getHost();
        ArrayList<String> params = calculatePayloads(baseRequestResponse, type, paramGrabber);
        ArrayList<String> valueParams = new ArrayList<>();
        for(int i=0; i<params.size();i++) {
            String candidate = params.get(i);
            if(candidate.contains("~")) {
                params.set(i, candidate.split("~", 2)[0]);
                valueParams.add(candidate);
            }
        }

        String attackID = Utilities.mangle(Arrays.hashCode(baseRequestResponse.getRequest())+"|"+System.currentTimeMillis());

        HashMap<String, String> requestParams = new HashMap<>();
        for (String entry: Keysmith.getAllKeys(baseRequestResponse.getRequest(), new HashMap<>())) {
            String[] parsed = Keysmith.parseKey(entry);
            requestParams.putIfAbsent(parsed[1], parsed[0]);
        }

        final String payload = ""; // formerly "<a`'\\\"${{\\\\"


        ParamInsertionPoint insertionPoint = getInsertionPoint(baseRequestResponse, type, payload, attackID);

        PayloadInjector injector = new PayloadInjector(baseRequestResponse, insertionPoint);

        Utilities.log("Initiating parameter name bruteforce on "+ targetURL);
        CircularFifoQueue<String> recentParams = new CircularFifoQueue<>(BUCKET_SIZE*3);

        Attack base = getBaselineAttack(injector);
        Attack paramGuess = null;
        Attack failAttack;

        //String ref = Utilities.getHeader(baseRequestResponse.getRequest(), "Referer");
        //HashMap<String, Attack> baselines = new HashMap<>();
        //baselines.put(ref, new Attack(baseRequestResponse));
        byte[] invertedBase = null;
        Attack altBase = null;
        boolean tryMethodFlip = false;
        if(baseRequestResponse.getRequest()[0] != 'G') {
            invertedBase = Utilities.helpers.toggleRequestMethod(baseRequestResponse.getRequest());
            altBase = new Attack(Utilities.callbacks.makeHttpRequest(baseRequestResponse.getHttpService(), invertedBase));
            if(Utilities.helpers.analyzeResponse(altBase.getFirstRequest().getResponse()).getStatusCode() != 404) {
                altBase.addAttack(new Attack(Utilities.callbacks.makeHttpRequest(baseRequestResponse.getHttpService(), invertedBase)));
                altBase.addAttack(new Attack(Utilities.callbacks.makeHttpRequest(baseRequestResponse.getHttpService(), invertedBase)));
                altBase.addAttack(new Attack(Utilities.callbacks.makeHttpRequest(baseRequestResponse.getHttpService(), invertedBase)));
                tryMethodFlip = true;
            }
        }

        // put the params into buckets
        Deque<ArrayList<String>> paramBuckets = new ArrayDeque<>();
        addParams(paramBuckets, valueParams);
        addParams(paramBuckets, params);


        while (paramBuckets.size() > 0) {
            ArrayList<String> candidates = paramBuckets.pop();
            String submission = String.join("|", candidates);
            paramGuess = injector.probeAttack(submission);

            if (!candidates.contains("~")) {
                if (findPersistent(baseRequestResponse, paramGuess, attackID, recentParams, candidates)) {
                    base = getBaselineAttack(injector);
                }
                recentParams.addAll(candidates); // fixme this results in params being found multiple times
            }

            Attack localBase;
            if (submission.contains("~")) {
                localBase = new Attack();
                localBase.addAttack(base);
            }
            else {
                localBase = base;
            }

            if (!Utilities.similar(localBase, paramGuess)) {
                Attack confirmParamGuess = injector.probeAttack(submission);

                failAttack = injector.probeAttack(Keysmith.permute(submission));

                // this to prevent error messages obscuring persistent inputs
                findPersistent(baseRequestResponse, failAttack, attackID, recentParams, null);

                localBase.addAttack(failAttack);
                if (!Utilities.similar(localBase, confirmParamGuess)) {

                    if(candidates.size() > 1) {
                        Utilities.log("Splitting "+ submission);
                        ArrayList<String> left = new ArrayList<>(candidates.subList(0, candidates.size() / 2));
                        Utilities.log("Got "+String.join("|",left));
                        ArrayList<String> right = new ArrayList<>(candidates.subList(candidates.size() / 2, candidates.size()));
                        Utilities.log("Got "+String.join("|",right));
                        paramBuckets.push(left);
                        paramBuckets.push(right);
                    }
                    else {
                        Probe validParam = new Probe("Found unlinked param: " + submission, 4, submission);
                        validParam.setEscapeStrings(Keysmith.permute(submission), Keysmith.permute(submission, false));
                        validParam.setRandomAnchor(false);
                        validParam.setPrefix(Probe.REPLACE);
                        ArrayList<Attack> confirmed = injector.fuzz(localBase, validParam);
                        if (!confirmed.isEmpty()) {
                            Utilities.out(targetURL + " identified parameter: " + candidates);
                            Utilities.callbacks.addScanIssue(Utilities.reportReflectionIssue(confirmed.toArray(new Attack[2]), baseRequestResponse, "Secret parameter"));

                            scanParam(insertionPoint, injector, submission.split("~", 2)[0]);
                        } else {
                            Utilities.out(targetURL + " questionable parameter: " + candidates);
                        }
                    }
                } else {
                    Utilities.log(targetURL + " couldn't replicate: " + candidates);
                    base.addAttack(paramGuess);
                }
            } else if (tryMethodFlip) {
                Attack paramGrab = new Attack(Utilities.callbacks.makeHttpRequest(baseRequestResponse.getHttpService(), invertedBase));
                findPersistent(baseRequestResponse, paramGrab, attackID, recentParams, null);

                if (!Utilities.similar(altBase, paramGrab)) {
                    Utilities.log("Potential GETbase param: " + candidates);
                    injector.probeAttack(Keysmith.permute(submission));
                    altBase.addAttack(new Attack(Utilities.callbacks.makeHttpRequest(baseRequestResponse.getHttpService(), invertedBase)));
                    injector.probeAttack(submission);

                    paramGrab = new Attack(Utilities.callbacks.makeHttpRequest(baseRequestResponse.getHttpService(), invertedBase));
                    if (!Utilities.similar(altBase, paramGrab)) {

                        if(candidates.size() > 1) {
                            Utilities.log("Splitting "+ submission);
                            ArrayList<String> left = new ArrayList<>(candidates.subList(0, candidates.size() / 2));
                            ArrayList<String> right = new ArrayList<>(candidates.subList(candidates.size() / 2 + 1, candidates.size()));
                            paramBuckets.push(left);
                            paramBuckets.push(right);
                        }
                        else {
                            Utilities.out("Confirmed GETbase param: " + candidates);
                            IHttpRequestResponse[] evidence = new IHttpRequestResponse[3];
                            evidence[0] = altBase.getFirstRequest();
                            evidence[1] = paramGuess.getFirstRequest();
                            evidence[2] = paramGrab.getFirstRequest();
                            Utilities.callbacks.addScanIssue(new CustomScanIssue(baseRequestResponse.getHttpService(), Utilities.getURL(baseRequestResponse), evidence, "Secret parameter", "Parameter name: '" + candidates + "'. Review the three requests attached in chronological order.", "Medium", "Tentative", "Investigate"));
                        }
                    }
                }
            }

//                    for (String key : Keysmith.getAllKeys(paramGuess.getFirstRequest().getResponse(), requestParams)) {
//                        String[] parsed = Keysmith.parseKey(key);
//                        if (!(params.contains(key) || params.contains(parsed[1]) || requestParams.containsKey(parsed[1]) || parsed[1].equals(candidates))) {
//                            Utilities.out("Found new key: " + key);
//                            params.add(i + 1, key); // fixme probably adds the key in the wrong format
//                            paramGrabber.saveParams(paramGuess.getFirstRequest());
//                        }
//                    }
        }


        Utilities.log("Parameter name bruteforce complete: "+targetURL);

        return attacks;
    }

    private void addParams(Deque<ArrayList<String>> paramBuckets, ArrayList<String> params) {
        int limit = params.size();
        for (int i = 0; i<limit+ BUCKET_SIZE; i+= BUCKET_SIZE) {
            ArrayList<String> bucket = new ArrayList<>();
            for(int k = 0; k< BUCKET_SIZE && i+k < limit; k++) {
                bucket.add(params.get(i+k));
            }
            paramBuckets.add(bucket);
        }
    }

    private void scanParam(ParamInsertionPoint insertionPoint, PayloadInjector injector, String scanBasePayload) {
        IHttpRequestResponse scanBaseAttack = injector.probeAttack(scanBasePayload).getFirstRequest();
        byte[] scanBaseGrep = Utilities.helpers.stringToBytes(insertionPoint.calculateValue(scanBasePayload));
        int start = Utilities.helpers.indexOf(scanBaseAttack.getRequest(), scanBaseGrep, true, 0, scanBaseAttack.getRequest().length);
        int end = start + scanBaseGrep.length;
        Utilities.doActiveScan(scanBaseAttack, new int[]{start, end});
    }

    private static boolean findPersistent(IHttpRequestResponse baseRequestResponse, Attack paramGuess, String attackID, CircularFifoQueue<String> recentParams, ArrayList<String> currentParams) {
        if (currentParams == null) {
            currentParams = new ArrayList<>();
        }

        byte[] failResp = paramGuess.getFirstRequest().getResponse();
        if (failResp == null) {
            return false;
        }

        for(Iterator<String> params = recentParams.iterator(); params.hasNext();) {
            String param = params.next();
            if(currentParams.contains(param)) {
                continue;
            }

            String canary = Utilities.toCanary(param.split("~", 2)[0]) + attackID;
            if (Utilities.helpers.indexOf(failResp, Utilities.helpers.stringToBytes(canary), false, 1, failResp.length - 1) != -1) {
                Utilities.out(Utilities.getURL(baseRequestResponse) + " identified persistent parameter: " + param);
                params.remove();
                Utilities.callbacks.addScanIssue(new CustomScanIssue(baseRequestResponse.getHttpService(), Utilities.getURL(baseRequestResponse), paramGuess.getFirstRequest(), "Secret parameter", "Found persistent parameter: '"+param+"'. Disregard the request and look for " + canary + " in the response", "High", "Firm", "Investigate"));
                return true;
            }
        }
        return false;
    }

    private static Attack getBaselineAttack(PayloadInjector injector) {
        Attack base = injector.probeAttack(Utilities.randomString(6));
        for(int i=0; i<4; i++) {
            base.addAttack(injector.probeAttack(Utilities.randomString((i+1)*(i+1))));
        }
        return base;
    }

    private static ParamInsertionPoint getInsertionPoint(IHttpRequestResponse baseRequestResponse, byte type, String payload, String attackID) {
        return type == IParameter.PARAM_JSON ?
                        new JsonParamNameInsertionPoint(baseRequestResponse.getRequest(), "guesser", payload, type, attackID) :
                        new RailsInsertionPoint(baseRequestResponse.getRequest(), "guesser", payload, type, attackID);
    }

    static ArrayList<Attack> guessBackendParams(IHttpRequestResponse baseRequestResponse, IScannerInsertionPoint insertionPoint) {

        String baseValue = insertionPoint.getBaseValue();
        PayloadInjector injector = new PayloadInjector(baseRequestResponse, insertionPoint);
        String targetURL = baseRequestResponse.getHttpService().getHost();
        Utilities.log("Initiating parameter name bruteforce on " + targetURL);

        final String breaker = "=%3c%61%60%27%22%24%7b%7b%5c";
        Attack base = injector.buildAttack(baseValue+"&"+Utilities.randomString(6)+ breaker, false);

        for(int i=0; i<4; i++) {
            base.addAttack(injector.buildAttack(baseValue+"&"+Utilities.randomString((i+1)*(i+1))+ breaker, false));
        }

        ArrayList<Attack> attacks = new ArrayList<>();
        try {
            for (int i = 0; i < Utilities.paramNames.size(); i++) { // i<Utilities.paramNames.size();
                String candidate = Utilities.paramNames.get(i);
                Attack paramGuess = injector.buildAttack(baseValue + "&" + candidate + breaker, false);
                if (!Utilities.similar(base, paramGuess)) {
                    Attack confirmParamGuess = injector.buildAttack(baseValue + "&" + candidate + breaker, false);
                    base.addAttack(injector.buildAttack(baseValue + "&" + candidate + "z"+breaker, false));
                    if (!Utilities.similar(base, confirmParamGuess)) {
                        Probe validParam = new Probe("Backend param: " + candidate, 4, "&" + candidate + breaker, "&" + candidate + "=%3c%62%60%27%22%24%7b%7b%5c");
                        validParam.setEscapeStrings("&" + Utilities.randomString(candidate.length()) + breaker, "&" + candidate + "z"+breaker);
                        validParam.setRandomAnchor(false);
                        ArrayList<Attack> confirmed = injector.fuzz(base, validParam);
                        if (!confirmed.isEmpty()) {
                            Utilities.out("Identified backend parameter: " + candidate);
                            attacks.addAll(confirmed);
                        }
                    } else {
                        base.addAttack(paramGuess);
                    }
                }

            }
            Utilities.log("Parameter name bruteforce complete: "+targetURL);
        }
        catch (RuntimeException e) {
            Utilities.log("Parameter name bruteforce aborted: "+targetURL);
        }

        return attacks;
    }

}

class OfferParamGuess implements IContextMenuFactory {
    private IBurpExtenderCallbacks callbacks;
    private ParamGrabber paramGrabber;
    private ThreadPoolExecutor taskEngine;

    public OfferParamGuess(final IBurpExtenderCallbacks callbacks, ParamGrabber paramGrabber, ThreadPoolExecutor taskEngine) {
        this.taskEngine = taskEngine;
        this.callbacks = callbacks;
        this.paramGrabber = paramGrabber;
    }

    @Override
    public List<JMenuItem> createMenuItems(IContextMenuInvocation invocation) {
        IHttpRequestResponse[] reqs = invocation.getSelectedMessages();
        List<JMenuItem> options = new ArrayList<>();

        if(reqs.length == 0) {
            return options;
        }

        JMenuItem probeButton = new JMenuItem("Guess GET parameters");
        probeButton.addActionListener(new TriggerParamGuesser(reqs, false, IParameter.PARAM_URL, paramGrabber, taskEngine));
        options.add(probeButton);

        if (reqs.length == 1 && reqs[0] != null) {
            IHttpRequestResponse req = reqs[0];
            byte[] resp = req.getRequest();
            if (Utilities.countMatches(resp, Utilities.helpers.stringToBytes("%253c%2561%2560%2527%2522%2524%257b%257b%255c")) > 0) {
                JMenuItem backendProbeButton = new JMenuItem("*Identify backend parameters*");
                backendProbeButton.addActionListener(new TriggerParamGuesser(reqs, true, IParameter.PARAM_URL, paramGrabber, taskEngine));
                options.add(backendProbeButton);
            }

            if (resp[0] == 'P') {
                IRequestInfo info = Utilities.helpers.analyzeRequest(req);
                List<IParameter> params = info.getParameters();

                HashSet<Byte> paramTypes = new HashSet<>();
                for (IParameter param : params) {
                    if (param.getType() != IParameter.PARAM_URL) {
                        paramTypes.add(param.getType());
                    }
                }

                for (Byte type : paramTypes) {
                    String humanType = "Unknown";
                    switch(type) {
                        case 0:
                            humanType = "URL";
                            break;
                        case 1:
                            humanType = "body";
                            break;
                        case 2:
                            humanType = "cookie";
                            break;
                        case 3:
                            humanType = "XML";
                            break;
                        case 4:
                            humanType = "XML attribute";
                            break;
                        case 5:
                            humanType = "multipart";
                            break;
                        case 6:
                            humanType = "JSON";
                            break;
                    }

                    JMenuItem postProbeButton = new JMenuItem("Guess " + humanType + " parameter");
                    postProbeButton.addActionListener(new TriggerParamGuesser(reqs, false, type, paramGrabber, taskEngine));
                    options.add(postProbeButton);
                }
            }
        }

        return options;
    }
}

class LengthCompare implements Comparator<String> {
    public int compare(String o1, String o2) {
        return Integer.compare(o1.length(), o2.length());
    }
}

class TriggerParamGuesser implements ActionListener, Runnable {
    private IHttpRequestResponse[] reqs;
    private boolean backend;
    private byte type;
    private ParamGrabber paramGrabber;
    private ThreadPoolExecutor taskEngine;

    TriggerParamGuesser(IHttpRequestResponse[] reqs, boolean backend, byte type, ParamGrabber paramGrabber, ThreadPoolExecutor taskEngine) {
        this.taskEngine = taskEngine;
        this.paramGrabber = paramGrabber;
        this.backend = backend;
        this.reqs = reqs;
        this.type = type;
    }

    public void actionPerformed(ActionEvent e) {
        Runnable runnable = new TriggerParamGuesser(reqs, backend, type, paramGrabber, taskEngine);
        (new Thread(runnable)).start();
    }

    public void run() {
        Utilities.out("Queuing "+reqs.length+" tasks");

        ArrayList<IHttpRequestResponse> reqlist = new ArrayList<>(Arrays.asList(reqs));
        int thread_count = taskEngine.getCorePoolSize();
        Queue<String> cache = new CircularFifoQueue<>(thread_count);
        HashSet<String> remainingHosts = new HashSet<>();

        int i = 0;
        // every pass adds at least one item from every host
        while(!reqlist.isEmpty()) {
            Utilities.out("Loop "+i++);
            Iterator<IHttpRequestResponse> left = reqlist.iterator();
            while (left.hasNext()) {
                IHttpRequestResponse req = left.next();
                String host = req.getHttpService().getHost();

                if (!cache.contains(host)) {
                    cache.add(host);
                    left.remove();
                    Utilities.out("Adding request on "+host+" to queue");
                    taskEngine.execute(new ParamGuesser(Utilities.callbacks.saveBuffersToTempFiles(req), backend, type, paramGrabber));
                } else {
                    remainingHosts.add(host);
                }
            }
            if (remainingHosts.size() <= 1) {
                left = reqlist.iterator();
                while (left.hasNext()) {
                    taskEngine.execute(new ParamGuesser(Utilities.callbacks.saveBuffersToTempFiles(left.next()), backend, type, paramGrabber));
                }
                break;
            }
            else {
                cache = new CircularFifoQueue<>(min(remainingHosts.size()-1, thread_count));
            }
        }

    }
}