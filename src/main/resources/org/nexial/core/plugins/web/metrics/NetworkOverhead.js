metrics.NetworkOverhead = formatPerfNum(JSON.parse(localStorage.getItem('n')).map(x => x.connectEnd - x.domainLookupStart).pop());