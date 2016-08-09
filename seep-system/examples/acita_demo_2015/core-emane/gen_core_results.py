#!/usr/bin/python

import subprocess,os,time,re,argparse,glob, pandas

from extract_results import *
from compute_stats import compute_cumulative_percentiles


def main(exp_dir):

    sim_env = os.environ.copy()
    print "Analysing logs in %s"%(exp_dir)       

    # Get src logfilename
    src_log = get_src_logfile(exp_dir) 

    # Get sink logfilename
    #sink_log = get_sink_logfile(exp_dir)

    finished_sink_log = get_finished_sink_logfile(exp_dir)
    print 'Found finished sink log: ', finished_sink_log

    sink_logs = get_sink_logfiles(exp_dir)
    print 'Found sink logs: ', sink_logs

    op_logs = get_processor_logfiles(exp_dir)

    # Get time src started sending
    with open(src_log, 'r') as src:
        # Get time src sent first message
        t_src_begin = src_tx_begin(src)
        #if not t_src_begin: raise Exception("Could not find t_src_begin.")
        if not t_src_begin: "WARNING: Could not find t_src_begin."

    with open(finished_sink_log, 'r') as sink:
        # Get time sink received first message
        t_finished_sink_begin = sink_rx_begin(sink)
        if not t_finished_sink_begin: raise Exception("Could not find t_sink_begin.")
    with open(finished_sink_log, 'r') as sink:
        (total_tuples, total_bytes, t_finished_sink_end) = sink_rx_end(sink)
        if not t_finished_sink_end: raise Exception("Could not find t_sink_end.")
    with open(finished_sink_log, 'r') as sink:
        rx_latencies = sink_rx_latencies(sink)
        #if not rx_latencies: raise Exception("Could not find any latencies.")
    with open(finished_sink_log, 'r') as sink:
        tuple_ids = sink_rx_tuple_ids(sink)

    #If there are replicated sinks, need to treat the sinks that didn't finish
    #first differently.
    t_min_sink_begin = t_finished_sink_begin 
    for sink_log in filter(lambda log: log != finished_sink_log, sink_logs):
	print 'Processing potentially unfinished log: ', sink_log
	with open(sink_log, 'r') as sink:
	    (tuples, bytez) = unfinished_sink_tuples(sink, t_finished_sink_end, tuple_ids)

	    #TODO: This will fail for sinks that didn't finish.
	    #(tuples, sink_total_bytes, t_sink_end) = sink_rx_end(sink)
	    #if not t_sink_end: raise Exception("Could not find t_sink_end.")
	    if tuples == 0: continue
	    total_tuples += tuples
	    total_bytes += bytez 
	    #max_t_sink_end = max(max_t_sink_end, t_sink_end)
	with open(sink_log, 'r') as sink:
	    # Get time sink received first message
	    t_sink_begin = sink_rx_begin(sink)
	    if not t_sink_begin: raise Exception("Could not find t_sink_begin.")
	    #if not t_sink_begin: t_min_sink_begin 

	    if t_sink_begin and t_sink_begin < t_min_sink_begin:
		t_min_sink_begin = t_sink_begin
	with open(sink_log, 'r') as sink:
	    rx_latencies += unfinished_sink_rx_latencies(sink, t_finished_sink_end)
	    #if not rx_latencies: raise Exception("Could not find any latencies.")

    deduped_tx_latencies = dedup_latencies(rx_latencies)

    op_tputs = {}
    op_interval_tputs = {}
    op_qlens = {}
    link_interval_tputs = {}
    for op_log in op_logs + sink_logs:
        with open(op_log, 'r') as f:
            (op_id, tput) = processor_tput(f)
            if op_id:
                op_tputs[op_id] = tput 

        with open(op_log, 'r') as f:
            (op_id, interval_tputs) = get_interval_tputs(f)
            if op_id:
                op_interval_tputs[op_id] = interval_tputs

        with open(op_log, 'r') as f:
            (op_id, qlens) = get_qlens(f)
            if op_id:
                op_qlens[op_id] = qlens 

        with open(op_log, 'r') as f:
            (op_id, interval_tputs) = get_link_interval_tputs(f)
            if op_id:
                for up_id in interval_tputs:
                    link_interval_tputs[(op_id, up_id)] = interval_tputs[up_id]
                
    # Record the tput, k, w, query name etc.
    # Compute the mean tput
    if t_src_begin: 
        src_sink_mean_tput = mean_tput(t_src_begin, t_finished_sink_end, total_bytes)
        record_stat('%s/tput.txt'%exp_dir, {'src_sink_mean_tput':src_sink_mean_tput})
        src_sink_frame_rate = frame_rate(t_src_begin, t_finished_sink_end, total_tuples) 
        record_stat('%s/tput.txt'%exp_dir, {'src_sink_frame_rate':src_sink_frame_rate}, 'a')


    record_sink_sink_stats(t_min_sink_begin, t_finished_sink_end, total_bytes, total_tuples, deduped_tx_latencies, exp_dir)
    """
    sink_sink_mean_tput = mean_tput(t_sink_begin, t_sink_end, total_bytes)
    record_stat('%s/tput.txt'%exp_dir, {'sink_sink_mean_tput':sink_sink_mean_tput}, 'a')
    sink_sink_frame_rate = frame_rate(t_sink_begin, t_sink_end, tuples) 
    record_stat('%s/tput.txt'%exp_dir, {'sink_sink_frame_rate':sink_sink_frame_rate}, 'a')

    lstats = latency_stats(rx_latencies)
    record_stat('%s/latency.txt'%exp_dir, lstats)
    """

    record_stat('%s/op-tputs.txt'%exp_dir, op_tputs)
    record_op_interval_tputs(op_interval_tputs, exp_dir)
    record_op_qlens(op_qlens, exp_dir)
    record_link_interval_tputs(link_interval_tputs, exp_dir)

def record_sink_sink_stats(t_sink_begin, t_sink_end, total_bytes, tuples, deduped_tx_latencies, exp_dir):
    sink_sink_mean_tput = mean_tput(t_sink_begin, t_sink_end, total_bytes)
    record_stat('%s/tput.txt'%exp_dir, {'sink_sink_mean_tput':sink_sink_mean_tput}, 'a')
    sink_sink_frame_rate = frame_rate(t_sink_begin, t_sink_end, tuples) 
    record_stat('%s/tput.txt'%exp_dir, {'sink_sink_frame_rate':sink_sink_frame_rate}, 'a')
    deduped_latencies = map(lambda (latency, txts): latency, deduped_tx_latencies.values())
    lstats = latency_stats(pd.Series(deduped_latencies))
    record_stat('%s/latency.txt'%exp_dir, lstats)
    lpercentiles = compute_cumulative_percentiles(deduped_latencies)
    record_percentiles(lpercentiles, 'lat', exp_dir)
    record_latencies(deduped_tx_latencies, '%s/tx_latencies.txt'%exp_dir)

def get_src_logfile(exp_dir):
    return get_logfile(exp_dir, is_src_log)

def get_sink_logfile(exp_dir):
    return get_logfile(exp_dir, is_sink_log)

def get_finished_sink_logfile(exp_dir):
    return get_logfile(exp_dir, is_finished_sink_log)

def get_logfile(exp_dir, type_fn):
    files = glob.glob("%s/*worker*.log"%exp_dir)
    for filename in files:
        with open(filename, 'r') as f:
            if type_fn(f): return filename 
    return None

def get_sink_logfiles(exp_dir):
    files = glob.glob("%s/*worker*.log"%exp_dir)
    filenames = []
    for filename in files:
        with open(filename, 'r') as f:
            if is_sink_log(f): filenames.append(filename) 
    return filenames 

def get_processor_logfiles(exp_dir):
    files = glob.glob("%s/*worker*.log"%exp_dir)
    filenames = []
    for filename in files:
        with open(filename, 'r') as f:
            if is_processor_log(f): filenames.append(filename) 
    return filenames 

def mean_tput(t_start, t_end, bites):
    duration_s = float(t_end - t_start) / 1000.0
    kb = (8.0 * float(bites)) / 1024.0 
    return kb / duration_s 

def frame_rate(t_start, t_end, tuples):
    duration_s = float(t_end - t_start) / 1000.0
    return float(tuples) / duration_s 

def latency_stats(latencies):
    result = {}
    percentiles=[.25,.5,.75,.9,.95,.99]
    for k,v in dict(latencies.describe(percentiles=percentiles)).iteritems():
        result['%s_lat'%k] = v
    return result

def record_stat(stat_file, stats, mode='w'):
    with open(stat_file, mode) as sf:
        for stat in stats:
            line = '%s=%s\n'%(str(stat), str(stats[stat]))
            print line
            sf.write(line)

def record_percentiles(percentiles, metric_suffix, exp_dir):
    with open('%s/cum-%s.data'%(exp_dir,metric_suffix),'w') as cum_fixed_kmobsession_plotdata:
        cum_fixed_kmobsession_plotdata.write('#p %s\n'%metric_suffix)
        for (p, value) in percentiles: 
            logline = '%.2f %d\n'%(p,value)
            cum_fixed_kmobsession_plotdata.write(logline)

def record_latencies(latencies, latencies_file):
    with open(latencies_file,'w') as lf:
        lf.write('# latency')
        tx_sorted = [] 
        for ts in latencies: 
            tx_sorted.append((latencies[ts][1], latencies[ts][0]))
        for (tx, latency) in sorted(tx_sorted): 
            lf.write('%d %d\n'%(tx/1000, latency))

def record_op_interval_tputs(op_interval_tputs, exp_dir):
    for op in op_interval_tputs:
        op_interval_tput_file = "%s/op_%s_interval_tput.txt"%(exp_dir, op)
        with open(op_interval_tput_file, 'w') as f:
            f.write('# tput cum\n')
            for (ts, tput, cum) in op_interval_tputs[op]:
                f.write('%d %.1f %.1f\n'%(ts/1000, tput, cum))

def record_link_interval_tputs(link_interval_tputs, exp_dir):
    links_tput_dir = "%s/link-tputs"%(exp_dir)
    if not os.path.exists(links_tput_dir): os.mkdir(links_tput_dir)
    for (op, up) in link_interval_tputs:
        link_interval_tput_file = "%s/link-tputs/op_%s_up_%s_link_interval_tput.txt"%(exp_dir, op, up)
        with open(link_interval_tput_file, 'w') as f:
            f.write('# tput cum cost\n')
            for (ts, tput, cum, cost) in link_interval_tputs[(op, up)]:
                f.write('%d %.1f %.1f %.1f\n'%(ts/1000, tput, cum, cost))

def record_op_qlens(op_qlens, exp_dir):
    for op in op_qlens:
        op_qlens_file = "%s/op_%s_qlens.txt"%(exp_dir, op)
        with open(op_qlens_file, 'w') as f:
            f.write('# qlen\n')
            for (ts, qlen) in op_qlens[op]:
                f.write('%d %d\n'%(ts/1000, qlen))

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='Analyse emulation logs')
    parser.add_argument('--expDir', dest='exp_dir', help='relative path to exp dir')
    args=parser.parse_args()

    main(args.exp_dir)

