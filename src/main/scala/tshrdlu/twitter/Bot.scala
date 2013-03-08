package tshrdlu.twitter

/**
 * Copyright 2013 Jason Baldridge
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import twitter4j._
import collection.JavaConversions._
import tshrdlu.util.English
import upparse.cli.Main
import upparse.corpus.{StopSegmentCorpus, BasicCorpus, CorpusUtil}
import sys.process._
import java.io._
import jarvis.nlp.TrigramModel
import jarvis.nlp.util._

object SPLReader extends CorpusReader(".txt") with SPLParser

/**
 * Base trait with properties default for Configuration.
 * Gets a Twitter instance set up and ready to use.
 */
trait TwitterInstance {
  val twitter = new TwitterFactory().getInstance
}

/**
 * A bot that can monitor the stream and also take actions for the user.
 */
class ReactiveBot extends TwitterInstance with StreamInstance {
  stream.addListener(new UserStatusResponder(twitter))
}

/**
 * Companion object for ReactiveBot with main method.
 */
object ReactiveBot {

  def main(args: Array[String]) {

    val bot = new ReactiveBot
    bot.stream.user
    
    // If you aren't following a lot of users and want to get some
    // tweets to test out, use this instead of bot.stream.user.
    //bot.stream.sample
  }

}


/**
 * A listener that looks for messages to the user and replies using the
 * doActionGetReply method. Actions can be doing things like following,
 * and replies can be generated just about any way you'd like. The base
 * implementation searches for tweets on the API that have overlapping
 * vocabulary and replies with one of those.
 */
class UserStatusResponder(twitter: Twitter) 
extends StatusListenerAdaptor with UserStreamListenerAdaptor {

  import tshrdlu.util.SimpleTokenizer
  import collection.JavaConversions._
  
  def printToFile(f: java.io.File)(op: java.io.PrintWriter => Unit) {
    val p = new java.io.PrintWriter(f)
    try { op(p) } finally { p.close() }
  }

  def writeTextToFile(filename: String, text: String) = {
    printToFile(new File(filename))(p => {
      p.println(text)
    })
  }

  private val tweetFile = "/u/spryor/tshrdlu/evalTweet.txt"
  private val upparseArgs: Array[String] = Array("chunk",
                                        "-chunkerType", "PRLG",
                                        "-chunkingStrategy", "UNIFORM",
                                        "-encoderType", "BIO",
                                        "-emdelta", ".0001",
                                        "-smooth", ".1",
                                        "-train", "/u/spryor/tshrdlu/data/finalTweetData.txt",
                                        "-test", tweetFile,
                                        "-trainFileType", "SPL",
                                        "-testFileType", "SPL")
  val upparser = new Main(upparseArgs)
  val model = {
    val m = upparser.chunk_special()
    while(m.anotherIteration()) {
      m.updateWithEM(upparser.outputManager.getStatusStream())
    }
    m
  }

  def chunkTweet(tweet: String) = {
    writeTextToFile(tweetFile, tweet)
    val newTweet = CorpusUtil.stopSegmentCorpus(upparser.evalManager.alpha,
      Array(tweetFile),
      upparser.evalManager.testFileType,
      upparser.evalManager.numSent,
      upparser.evalManager.filterLength,
      upparser.evalManager.noSeg,
      upparser.evalManager.reverse)
    val chunkerOutput = model.getCurrentChunker().getChunkedCorpus(newTweet)
    chunkerOutput.clumps2str(chunkerOutput.getArrays()(0))
  }

  def extractChunks(chunkedTweet: String) = {
    (Chunks findAllIn chunkedTweet).map(_.replaceAll("[^a-zA-Z\\s]+","")).toIndexedSeq
  }

  val LanguageModel = TrigramModel(SPLReader("/u/spryor/tshrdlu/data/"))

  val username = twitter.getScreenName
  
  lazy val Chunks = """[(]([^()]+)[)]""".r
 
  // Recognize the follow command to follow students in ANLP
  lazy val FollowANLPPeople = """(?i).*follow anlp people.*""".r

  // Recognize a follow command
  lazy val FollowRE = """(?i)(?<=follow)(\s+(me|@[a-z]+))+""".r

  // Pull just the lead mention from a tweet.
  lazy val StripLeadMentionRE = """(?:)^@[a-z_0-9]+\s(.*)$""".r

  // Pull the RT and mentions from the front of a tweet.
  lazy val StripMentionsRE = """(?:)(?:RT\s)?(?:(?:@[a-z]+\s))+(.*)$""".r   
  override def onStatus(status: Status) {
    println("New status: " + status.getText)
    val replyName = status.getInReplyToScreenName
    if (replyName == username) {
      println("*************")
      println("New reply: " + status.getText)
      //println("New reply (chunked): " + chunkTweet(status.getText))
      val text = "@" + status.getUser.getScreenName + " " + doActionGetReply(status)
      println("Repsonlying: " + text)
      val reply = new StatusUpdate(text).inReplyToStatusId(status.getId)
      twitter.updateStatus(reply)
    }
  }
 
  /**
   * A method that possibly takes an action based on a status
   * it has received, and then produces a response.
   */
  def doActionGetReply(status: Status) = {
    val text = status.getText.toLowerCase
    val followMatches = FollowRE.findAllIn(text)
    val followANLP = FollowANLPPeople.findAllIn(text)
    if(!followANLP.isEmpty) {
      val followerIds = twitter.getFollowersIDs("appliednlp",-1).getIDs
      val idsToFollow = followerIds.filter{id => {
        val user = twitter.showUser(id)
        val screenName = user.getScreenName
        screenName.endsWith("_anlp") && screenName != status.getUser.getScreenName
      }}
      idsToFollow.foreach(x => println("Adding: " + x))
      idsToFollow.foreach(twitter.createFriendship)
      "OK. I FOLLOWED THE ANLP PEOPLE"
    } else if (!followMatches.isEmpty) {
      val followSet = followMatches
	.next
	.drop(1)
	.split("\\s")
	.map {
	  case "me" => status.getUser.getScreenName
	  case screenName => screenName.drop(1)
	}
	.toSet
      followSet.foreach(twitter.createFriendship)
      "OK. I FOLLOWED " + followSet.map("@"+_).mkString(" ") + "."  
    } else {
      
      try {
	val StripLeadMentionRE(withoutMention) = text
        val chunks = extractChunks(chunkTweet(withoutMention))
        val selectedChunks = chunks
                     .map(c => (LanguageModel(SimpleTokenizer(c)), c))
                     .sorted
                     .take(2)
                     .map(_._2)
        println("USING: " + selectedChunks.mkString(", "))
        val statusList = selectedChunks.toList
         .flatMap(w => twitter.search(new Query(w)).getTweets)
	extractText(statusList)
      }	catch { 
	case _: Throwable => "NO."
      }
    }
  
  }

  /**
   * Go through the list of Statuses, filter out the non-English ones,
   * strip mentions from the front, filter any that have remaining
   * mentions, and then return the head of the set, if it exists.
   */
  def extractText(statusList: List[Status]) = {
    val useableTweets = statusList
      .map(_.getText)
      .map {
	case StripMentionsRE(rest) => rest
	case x => x
      }
      .filterNot(_.contains('@'))
      .filterNot(_.contains('/'))
      .filter(tshrdlu.util.English.isEnglish)
      .filter(tshrdlu.util.English.isSafe)
      .map(t => (LanguageModel(SimpleTokenizer(t)), t))
      .sorted
      .reverse
      .map{ case (k,t) => t}
    if (useableTweets.isEmpty) "NO." else useableTweets.head
  }

}

