SELECT * FROM t1 LEFT OUTER JOIN t2 on t1.id = t2.xid RIGHT OUTER JOIN t3 on t1.id = t3.yid INNER JOIN t4 USING (a,b,c)