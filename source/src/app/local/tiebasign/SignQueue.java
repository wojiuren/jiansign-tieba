package app.local.tiebasign;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Start with four requests; optionally grow to eight after confirmed successes. No retries. */
public final class SignQueue {
    public interface Request { TiebaApi.Result sign(TiebaApi.Forum forum) throws Exception; }
    public interface Listener { void finished(TiebaApi.Forum forum,TiebaApi.Result result,Exception error,int processed); }
    public static final class Stats {
        public int success,already,failed,unconfirmed,processed,total,peakInFlight;
        public boolean stopped;
    }
    private static final class Reply {
        final TiebaApi.Forum forum;final TiebaApi.Result result;final Exception error;
        Reply(TiebaApi.Forum forum,TiebaApi.Result result,Exception error){this.forum=forum;this.result=result;this.error=error;}
    }
    public static Stats run(List<TiebaApi.Forum> forums,Request request,TiebaApi.Stop stop,Listener listener) throws InterruptedException {
        return run(forums,request,stop,listener,4);
    }
    public static Stats run(List<TiebaApi.Forum> forums,Request request,TiebaApi.Stop stop,Listener listener,int maxConcurrency) throws InterruptedException {
        if(maxConcurrency!=4 && maxConcurrency!=8)throw new IllegalArgumentException("Supported concurrency: 4 or 8");
        Stats stats=new Stats();stats.total=forums.size();List<TiebaApi.Forum> pending=new ArrayList<>();
        for(TiebaApi.Forum forum:forums) {
            if(stop.requested()){stats.stopped=true;return stats;}
            if(forum.signed){stats.already++;stats.processed++;listener.finished(forum,new TiebaApi.Result(TiebaApi.Outcome.ALREADY,"今天已签到"),null,stats.processed);}
            else pending.add(forum);
        }
        if(pending.isEmpty())return stats;
        ExecutorService pool=Executors.newFixedThreadPool(maxConcurrency);
        CompletionService<Reply> completion=new ExecutorCompletionService<>(pool);
        AtomicBoolean haltSignal=new AtomicBoolean();
        int index=0,inFlight=0;boolean halt=false;
        try {
            while(index<pending.size() || inFlight>0) {
                if(stop.requested() || haltSignal.get())halt=true;
                int limit=stats.success>=4?maxConcurrency:4;
                while(!halt && inFlight<limit && index<pending.size()) {
                    if(stop.requested() || haltSignal.get()){halt=true;break;}
                    TiebaApi.Forum forum=pending.get(index++);
                    completion.submit(()->{try {if(stop.requested())throw new InterruptedException();TiebaApi.Result result=request.sign(forum);if(result.outcome==TiebaApi.Outcome.STOP || result.outcome==TiebaApi.Outcome.UNCONFIRMED)haltSignal.set(true);return new Reply(forum,result,null);}catch(Exception e){haltSignal.set(true);return new Reply(forum,null,e);}});inFlight++;
                    stats.peakInFlight=Math.max(stats.peakInFlight,inFlight);
                }
                if(inFlight==0)break;
                Future<Reply> future=completion.poll(200,TimeUnit.MILLISECONDS);if(future==null)continue;
                Reply reply;
                try {reply=future.get();}catch(ExecutionException e){throw new IllegalStateException("Unexpected signing worker failure",e);}
                inFlight--;stats.processed++;
                if(reply.error!=null){stats.unconfirmed++;halt=true;}
                else if(reply.result.outcome==TiebaApi.Outcome.UNCONFIRMED){stats.unconfirmed++;halt=true;}
                else if(reply.result.outcome==TiebaApi.Outcome.SUCCESS)stats.success++;
                else if(reply.result.outcome==TiebaApi.Outcome.ALREADY)stats.already++;
                else {stats.failed++;if(reply.result.outcome==TiebaApi.Outcome.STOP)halt=true;}
                listener.finished(reply.forum,reply.result,reply.error,stats.processed);
            }
            stats.stopped=halt;return stats;
        }finally{pool.shutdownNow();}
    }
}
