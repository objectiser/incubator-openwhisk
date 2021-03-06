/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.database

import java.io.InputStream
import java.io.OutputStream

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import akka.http.scaladsl.model.ContentType
import akka.stream.IOResult
import akka.stream.scaladsl.StreamConverters
import spray.json.JsObject
import whisk.common.TransactionId
import whisk.core.entity.CacheKey
import whisk.core.entity.DocId
import whisk.core.entity.DocInfo
import whisk.core.entity.DocRevision

/**
 * An interface for modifying the revision number on a document. Hides the details of
 * the revision to some extent while providing a marker interface for operations that
 * need to update the revision on a document.
 */
protected[core] trait DocumentRevisionProvider {

  /** Gets the document id and revision as an instance of DocInfo. */
  protected[database] def docinfo: DocInfo

  /**
   * Sets the revision number when a document is deserialized from datastore. The
   * _rev is an opaque value, needed to update the record in the datastore. It is
   * not part of the core properties of this class. It is not required when saving
   * a new instance of this type to the datastore.
   */
  protected[core] final def revision[W](r: DocRevision): W = {
    _rev = r
    this.asInstanceOf[W]
  }

  protected[core] def rev = _rev

  private var _rev: DocRevision = DocRevision.empty
}

/**
 * A common trait for all records that are serialized into raw documents for
 * the datastore, where the document id is a generated unique identifier.
 */
trait DocumentSerializer {

  /**
   * A JSON view including the document metadata, for writing to the datastore.
   *
   * @return JsObject
   */
  def toDocumentRecord: JsObject
}

/**
 * A common trait for all records that are deserialized from raw documents in the datastore
 *
 * The type parameter W represents the "whisk" type, the document abstraction to
 * use in core components. The trait is invariant in W
 * but the get permits a datastore of its super type so that a single datastore client
 * may be used for multiple types (because the types are stored in the same database for example).
 */
trait DocumentFactory[W <: DocumentRevisionProvider] extends MultipleReadersSingleWriterCache[W, DocInfo] {

  /**
   * Puts a record of type W in the datastore.
   *
   * The type parameters for the database are bounded from below to allow gets from a database that
   * contains several different but related types (for example entities are stored in the same database
   * and share common super types EntityRecord and WhiskEntity.
   *
   * @param db the datastore client to fetch entity from
   * @param doc the entity to store
   * @param transid the transaction id for logging
   * @param notifier an optional callback when cache changes
   * @return Future[DocInfo] with completion to DocInfo containing the save document id and revision
   */
  def put[Wsuper >: W](db: ArtifactStore[Wsuper], doc: W)(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[DocInfo] = {
    Try {
      require(db != null, "db undefined")
      require(doc != null, "doc undefined")
    } map { _ =>
      implicit val logger = db.logging
      implicit val ec = db.executionContext

      val key = CacheKey(doc)
      val docInfo = doc.docinfo

      cacheUpdate(doc, key, db.put(doc) map { newDocInfo =>
        doc.revision[W](newDocInfo.rev)
        doc.docinfo
      })
    } match {
      case Success(f) => f
      case Failure(t) => Future.failed(t)
    }
  }

  def attach[Wsuper >: W](db: ArtifactStore[Wsuper],
                          doc: W,
                          attachmentName: String,
                          contentType: ContentType,
                          bytes: InputStream,
                          postProcess: Option[W => W] = None)(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[DocInfo] = {

    Try {
      require(db != null, "db undefined")
      require(doc != null, "doc undefined")
    } map { _ =>
      implicit val logger = db.logging
      implicit val ec = db.executionContext

      val key = CacheKey(doc)
      val docInfo = doc.docinfo
      val src = StreamConverters.fromInputStream(() => bytes)
      val cacheDoc = postProcess map { _(doc) } getOrElse doc

      cacheUpdate(cacheDoc, key, db.attach(docInfo, attachmentName, contentType, src) map { newDocInfo =>
        cacheDoc.revision[W](newDocInfo.rev)
        cacheDoc.docinfo
      })
    } match {
      case Success(f) => f
      case Failure(t) => Future.failed(t)
    }
  }

  def del[Wsuper >: W](db: ArtifactStore[Wsuper], doc: DocInfo)(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[Boolean] = {
    Try {
      require(db != null, "db undefined")
      require(doc != null, "doc undefined")
    } map { _ =>
      implicit val logger = db.logging
      implicit val ec = db.executionContext

      val key = CacheKey(doc.id.asDocInfo)
      cacheInvalidate(key, db.del(doc))
    } match {
      case Success(f) => f
      case Failure(t) => Future.failed(t)
    }
  }

  /**
   * Fetches a raw record of type R from the datastore by its id (and revision if given)
   * and converts it to Success(W) or Failure(Throwable) if there is an error fetching
   * the record or deserializing it.
   *
   * The type parameters for the database are bounded from below to allow gets from a database that
   * contains several different but related types (for example entities are stored in the same database
   * and share common super types EntityRecord and WhiskEntity.
   *
   * @param db the datastore client to fetch entity from
   * @param doc the entity document information (must contain a valid id)
   * @param rev the document revision (optional)
   * @param fromCache will only query cache if true (defaults to collection settings)
   * @param transid the transaction id for logging
   * @param mw a manifest for W (hint to compiler to preserve type R for runtime)
   * @return Future[W] with completion to Success(W), or Failure(Throwable) if the raw record cannot be converted into W
   */
  def get[Wsuper >: W](
    db: ArtifactStore[Wsuper],
    doc: DocId,
    rev: DocRevision = DocRevision.empty,
    fromCache: Boolean = cacheEnabled)(implicit transid: TransactionId, mw: Manifest[W]): Future[W] = {
    Try {
      require(db != null, "db undefined")
    } map {
      implicit val logger = db.logging
      implicit val ec = db.executionContext
      val key = doc.asDocInfo(rev)
      _ =>
        cacheLookup(CacheKey(key), db.get[W](key), fromCache)
    } match {
      case Success(f) => f
      case Failure(t) => Future.failed(t)
    }
  }

  def getAttachment[Wsuper >: W](
    db: ArtifactStore[Wsuper],
    doc: W,
    attachmentName: String,
    outputStream: OutputStream,
    postProcess: Option[W => W] = None)(implicit transid: TransactionId, mw: Manifest[W]): Future[W] = {

    implicit val ec = db.executionContext
    implicit val notifier: Option[CacheChangeNotification] = None

    Try {
      require(db != null, "db defined")
      require(doc != null, "doc undefined")
    } map { _ =>
      implicit val logger = db.logging
      implicit val ec = db.executionContext

      val docInfo = doc.docinfo
      val key = CacheKey(docInfo)
      val sink = StreamConverters.fromOutputStream(() => outputStream)

      db.readAttachment[IOResult](docInfo, attachmentName, sink).map {
        case _ =>
          val cacheDoc = postProcess map { _(doc) } getOrElse doc

          cacheUpdate(cacheDoc, key, Future.successful(docInfo)) map { newDocInfo =>
            cacheDoc.revision[W](newDocInfo.rev)
          }
          cacheDoc
      }

    } match {
      case Success(f) => f
      case Failure(t) => Future.failed(t)
    }
  }

  def deleteAttachments[Wsuper >: W](db: ArtifactStore[Wsuper], doc: DocInfo)(
    implicit transid: TransactionId): Future[Boolean] = {
    Try {
      require(db != null, "db defined")
      require(doc != null, "doc undefined")
    } map { _ =>
      implicit val ec = db.executionContext
      db.deleteAttachments(doc)
    } match {
      case Success(f) => f
      case Failure(t) => Future.failed(t)
    }
  }
}
