(ns clojurechat.core
  (:gen-class)
  (:require [clojure.string :as str]) 
  (:import (java.net InetAddress SocketException Socket ServerSocket DatagramSocket DatagramPacket)
           (java.io OutputStreamWriter InputStreamReader IOException BufferedReader PrintWriter DataInputStream DataOutputStream)))

(def channels (atom (hash-map)))
(def users (atom (hash-map)))

(defn user-quit [socket nick p]
  (println "closing connection")
  (.close socket)
  ;; NOTE: Instead of `doseq` you can do this with `apply` probably
  (doseq [chan ((@users (keyword nick)) :admins)]
    (swap! channels assoc (keyword chan) (assoc (@channels (keyword chan)) :admin (vec (remove #{nick} ((@channels (keyword chan)) :admin)))))

    (println (empty? ((@channels (keyword chan)) :admin)))
    (println (empty? ((@channels (keyword chan)) :people)))
    (when (empty? ((@channels (keyword chan)) :admin))
      (if (empty? ((@channels (keyword chan)) :people)) (swap! channels dissoc (keyword chan)) 
        (do (swap! channels assoc (keyword chan) 
                   (assoc (@channels (keyword chan)) :admin (conj ((@channels (keyword chan)) :admin) (first ((@channels (keyword chan)) :people)))))
            (swap! channels assoc (keyword chan) 
                   (assoc (@channels (keyword chan)) :people (remove #{(first ((@channels (keyword chan)) :people))} ((@channels (keyword )) :people)))))
        ))
    )
  (doseq [chan ((@users (keyword nick)) :channels)]
    (swap! channels assoc (keyword chan) (assoc (@channels (keyword chan)) :admin (vec (remove #{nick} ((@channels (keyword chan)) :admin)))))
    )

  (swap! @users dissoc (keyword nick))

  (deliver p false))

(defn loop-people [people admins f]
  (doseq [i people] (f i))
  (doseq [i admins] (f i)))

(defn handle-topic [channel topic writer nick]
  (let [kchannel (keyword channel)
        username ((@users (keyword nick)) :username)
        hostname ((@users (keyword nick)) :hostname)
        curchannel (@channels kchannel)]
    (when (not (nil? channel))
      (swap! channels assoc kchannel (assoc curchannel :topic (apply str topic)))
      (loop-people (curchannel :people) (curchannel :admin)
                   #(as-> ((@users (keyword %)) :socket) socket 
                      (.getOutputStream socket)
                      (new PrintWriter socket true)
                      (do  
                        (.println socket (str ":" nick "!" username "@" hostname " TOPIC " channel " " (str/join " " topic)))
                        (.println socket (str ":127.0.0.1 333 " nick " " channel " " nick " " (quot (System/currentTimeMillis) 1000)))
                      ))))))

(defn handle-message [dest content nick]
  (let [hostname ((@users (keyword nick)) :hostname)
        username ((@users (keyword nick)) :username)
        kchannel (keyword dest)]

    (loop-people ((@channels kchannel) :people) ((@channels kchannel) :admin)
                 #(when (not (= % nick))
                    (as-> ((@users (keyword %)) :socket) socket 
                      (.getOutputStream socket)
                      (new PrintWriter socket true)
                      (.println socket (str ":" nick "!" username "@" hostname " PRIVMSG " dest " " (str/join " " content))))))))

(defn handle-join [writer channel socket nick] 
  (let [hostname ((@users (keyword nick)) :hostname)
        username ((@users (keyword nick)) :username)
        kchannel (keyword channel)]
    (.println writer (str ":" username "!" nick "@" hostname " JOIN " channel)) 
    (if (contains? @channels kchannel)
      (let
        [curchannel (@channels kchannel)]
        (do
          (loop-people ((@channels kchannel) :people) ((@channels kchannel) :admin)
                       #(when (not (= % nick))
                          (as-> ((@users (keyword %)) :socket) socket 
                            (.getOutputStream socket)
                            (new PrintWriter socket true)
                            (.println socket (str ":" nick "!" username "@" hostname " JOIN " channel)))))

          (swap! channels assoc kchannel {:admin (curchannel :admin)
                                          :people (conj (curchannel :people) nick) 
                                          :topic (curchannel :topic)})

          (swap! users assoc (keyword nick) (conj ((@users (keyword nick)) :channels) channel))

          (when (not (nil? (curchannel :topic)))
            (.println writer (str ":127.0.0.1 332 " nick " " channel " " (curchannel :topic))))

          (.println writer (str ":127.0.0.1 353 " nick " = " channel " :" nick " " "@" (peek (curchannel :admin)) " " (str/join " " ((@channels kchannel) :people)))) 
          ;; TODO: (peek (curchannel :admin)) and should be substituted to accomodate more than one admin per channel
          (.println writer (str ":127.0.0.1 366 " nick " " channel " :END of /NAMES list."))))

      (do
        (swap! channels assoc (keyword channel) {:admin (vector nick)
                                                 :people [] 
                                                 :topic nil})

        (swap! users assoc (keyword nick) (assoc (@users (keyword nick)) :admins (conj ((@users (keyword nick)) :admins) channel)))
        (.println writer (str ":127.0.0.1 353 " nick " = " channel " :@" nick))
        (.println writer (str ":127.0.0.1 366 " nick " " channel " :END of /NAMES list.")))))
  (println @channels)
  )

(defn handle-nick [writer nick socket] 
  (if (@users (keyword nick)) nil
    (swap! users assoc (keyword nick) {
                                       :username nil
                                       :socket socket 
                                       :hostname nil 
                                       :channels []
                                       :admins []
                                       }))
  (.println writer (str "NICK " nick)))

(defn ping-client [socket nick out p]
  (future 
    (loop []
      (when (try
              (.write out (.getBytes "PING something\n")) ;; Тут DataOutputStream потому что только он ерорится
              (Thread/sleep 600000)
              true
              (catch Exception e
                (when (not (realized? p)) (user-quit socket nick p))
                false))
        (recur)))))

(defn handle-user [writer username zero perms realname socket nick pong out] 
  (let [nickkey (keyword nick)
        hostname (.getHostName (.getInetAddress socket))]
    (println nick)
    (println hostname)
    (println socket)
    (println realname)
    (println @users)
    (if (contains? @users nickkey)
      (do (swap! users assoc nickkey {:username username
                                      :socket socket
                                      :hostname hostname
                                      :channels [] ;; NOTE: this might give problems if user modifies username
                                      :admins []
                                      })
          (ping-client socket nick out pong))
      nil)
    (println @users))
  (.println writer (str ":127.0.0.1 001 " username " :Welcome to BunnyIRC " username))) ;; TODO: move this to if contains?


(defn message-handler [^Long port]
  (def ^:dynamic nick (atom nil))
  (def server-socket (new ServerSocket port))
  (while true
    (def socket (.accept server-socket))
    ;(def sockets (assoc sockets socket))
    ;(println "New connection thread started," (count sockets) "clients connected")
    (let [lsocket socket
          in (new BufferedReader (InputStreamReader. (.getInputStream lsocket)))
          out (DataOutputStream. (.getOutputStream lsocket))
          writer (new PrintWriter (.getOutputStream lsocket) true)
          pong (promise)
          nick (atom nil)]
      (future 
        (let [nick (atom nil)]
          (loop []
            (when (.ready in)
              (let [data (.readLine in)
                    x (str/split data #"\s")] ;; You can make this one `let`
                (if (empty? x) 
                  nil
                  (try 
                    (case (nth x 0)
                      ;; NOTE `out` is not really needed here as it can be created from socket with nick
                      "NICK" (do (reset! nick (nth x 1)) (handle-nick writer (nth x 1) lsocket))
                      "JOIN" (handle-join writer (nth x 1) lsocket @nick)
                      "USER" (handle-user writer (nth x 1) (nth x 2) (nth x 3) (nth x 4) lsocket @nick pong out)  
                      "PRIVMSG" (handle-message (nth x 1) (subvec x 2) @nick)
                      "TOPIC" (handle-topic (nth x 1) (subvec x 2) writer @nick)
                      "QUIT" (user-quit lsocket @nick pong)
                      nil)
                    (catch IndexOutOfBoundsException e (.println writer (str ":127.0.0.1 461 " (nth x 0) " :Not enough parameters")))
                    ))))
            (if realized? pong) nil (recur)))))))

(defn -main [& args]
  (message-handler 6667))
