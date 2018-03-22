# jepsen.etcdemo

lein run test --nodes-file ~/nodes --username admin

~/nodes  
This should contain a line delimited list of db nodes.

https://github.com/jepsen-io/jepsen/blob/master/doc/tutorial/01-scaffolding.md

```
...
INFO [2018-03-21 16:38:16,112] main - jepsen.core {:linear
 {:valid? true,
  :configs
  ({:model {:value 1},
    :last-op
    {:process 1,
     :type :ok,
     :f :read,
     :value 1,
     :index 107,
     :time 11847563146},
    :pending []}),
  :final-paths ()},
 :perf
 {:latency-graph {:valid? true},
  :rate-graph {:valid? true},
  :valid? true},
 :timeline {:valid? true},
 :valid? true}


Everything looks good! ヽ(‘ー`)ノ
```

`:valid` may be `indeterminate` or `unknown`. This means that it was unable to check. Possibly due to low memory or taking too long. Either use larger machine or rewrite test because it is poor.

`:configs` is the state of the world just before things went bad

`:last-op` and `:previous-ok` are different interpretations of the last known good point.

`:op` is (with linearizable checker) the furthest state we could reach with the linearizable checker just when things went wrong.
