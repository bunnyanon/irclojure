(ns clojurechat.core
  (:gen-class)
  (:import (java.net Socket ServerSocket)
           (java.util Scanner)
           (java.io IOException PrintWriter DataInputStream DataOutputStream)))

(defn send-message [^Long port]
  (def soc (new Socket "127.0.0.1" 6969))
  (def out (.getOutputStream soc))
  (def in (.getInputStream soc))
  (def scanner (new Scanner in))
  (def content "")
  (def writer (new PrintWriter out true))
  (.println writer "hi")

  ;(while (.hasNext scanner) (def content (str content (.next scanner))))
  (println (.next scanner))
  (println (.hasNext scanner))
  (println (.next scanner))
  (.hasNext scanner)
  (.next scanner)
  ;(.close soc)
  content
)

(send-message 6969)
(println "test")

(defn -main [& args]
  (send-message 6969))
