import React, { useState, useRef, useEffect } from 'react'
import type { ReactNode } from 'react'
import { Platform, Alert, AppState } from 'react-native'
import { SafeAreaProvider } from 'react-native-safe-area-context'
import DocumentPicker from 'react-native-document-picker'
import type { DocumentPickerResponse } from 'react-native-document-picker'
import { Chat, darkTheme } from '@flyerhq/react-native-chat-ui'

import type { MessageType } from '@flyerhq/react-native-chat-ui'
import json5 from 'json5'
import ReactNativeBlobUtil from 'react-native-blob-util'
import type { LlamaContext } from 'llama.rn'
import {
  initLlama,
  loadLlamaModelInfo,
  toggleNativeLog,
  addNativeLogListener,
  // eslint-disable-next-line import/no-unresolved
} from 'llama.rn'
import { Bubble } from './Bubble'

// Create a custom theme by extending the darkTheme
const customTheme = {
  ...darkTheme,
  colors: {
    ...darkTheme.colors,
    background: '#f5f5f5', // Change this to your desired background color
    inputBackground: '#333333', // Change input box background
    secondary: '#0d010c', // This will change the assistant bubble color
    primary: '#4A90E2',   // User bubble color - add or modify this line
  }
}

// Example: Catch logs from llama.cpp
toggleNativeLog(true)
addNativeLogListener((level, text) => {
  // eslint-disable-next-line prefer-const
  let log = (t: string) => t // noop
  // Uncomment to test:
  // ({log} = console)
  log(
    ['[rnllama]', level ? `[${level}]` : '', text].filter(Boolean).join(' '),
  )
})

const { dirs } = ReactNativeBlobUtil.fs

// Example grammar for output JSON
const testGbnf = `root   ::= object
value  ::= object | array | string | number | ("true" | "false" | "null") ws

object ::=
  "{" ws (
            string ":" ws value
    ("," ws string ":" ws value)*
  )? "}" ws

array  ::=
  "[" ws (
            value
    ("," ws value)*
  )? "]" ws

string ::=
  "\\"" (
    [^"\\\\\\x7F\\x00-\\x1F] |
    "\\\\" (["\\\\bfnrt] | "u" [0-9a-fA-F]{4}) # escapes
  )* "\\"" ws

number ::= ("-"? ([0-9] | [1-9] [0-9]{0,15})) ("." [0-9]+)? ([eE] [-+]? [0-9] [1-9]{0,15})? ws

# Optional space: by convention, applied in this grammar after literal chars when allowed
ws ::= | " " | "\\n" [ \\t]{0,20}`

// Default model URL - small Llama 3.2 model that works well on mobile
const DEFAULT_MODEL_URL = 'https://huggingface.co/hugging-quants/Llama-3.2-1B-Instruct-Q8_0-GGUF/resolve/main/llama-3.2-1b-instruct-q8_0.gguf'

const randId = () => Math.random().toString(36).substr(2, 9)

const user = { id: 'y9d7f8pgn' }

const systemId = 'h3o3lc5xj'
const system = { id: systemId }

const systemMessage = {
  role: 'system',
  content:
    'This is a conversation between user and assistant, a friendly chatbot.\n\n',
}

const defaultConversationId = 'default'

const renderBubble = ({
  child,
  message,
}: {
  child: ReactNode
  message: MessageType.Any
}) => <Bubble child={child} message={message} />

export default function App() {
  const [context, setContext] = useState<LlamaContext | undefined>(undefined)
  const [inferencing, setInferencing] = useState<boolean>(false)
  const [messages, setMessages] = useState<MessageType.Any[]>([])
  const [downloading, setDownloading] = useState<boolean>(false)
  const [downloadProgress, setDownloadProgress] = useState<number>(0)
  const [downloadSpeed, setDownloadSpeed] = useState<string>('')
  const [downloadPhase, setDownloadPhase] = useState<string>('')

  const conversationIdRef = useRef<string>(defaultConversationId)
  const lastModelPathRef = useRef<string | null>(null)

  const addMessage = (message: MessageType.Any, batching = false) => {
    if (batching) {
      // This can avoid the message duplication in a same batch
      setMessages([message, ...messages])
    } else {
      setMessages((msgs) => [message, ...msgs])
    }
  }

  const addSystemMessage = (text: string, metadata = {}) => {
    const textMessage: MessageType.Text = {
      author: system,
      createdAt: Date.now(),
      id: randId(),
      text,
      type: 'text',
      metadata: { system: true, ...metadata },
    }
    addMessage(textMessage)
    return textMessage.id
  }

  const handleReleaseContext = async () => {
    if (!context) return
    addSystemMessage('Releasing context...')
    context
      .release()
      .then(() => {
        setContext(undefined)
        addSystemMessage('Context released!')
      })
      .catch((err) => {
        addSystemMessage(`Context release failed: ${err}`)
      })
  }

  // Calculate SHA-256 hash of a file
  const calculateFileHash = async (filePath: string): Promise<string> => {
    try {
      // Read file in chunks to avoid memory issues with large files
      const CHUNK_SIZE = 4 * 1024 * 1024; // 4MB chunks
      const fileSize = (await ReactNativeBlobUtil.fs.stat(filePath)).size;
      const totalChunks = Math.ceil(fileSize / CHUNK_SIZE);
      
      // Initialize hash object
      const crypto = require('crypto-js');
      let hashObj = crypto.algo.SHA256.create();
      
      setDownloadPhase('Verifying file integrity...');
      
      for (let i = 0; i < totalChunks; i++) {
        const start = i * CHUNK_SIZE;
        const end = Math.min(start + CHUNK_SIZE, fileSize) - 1;
        
        // Read chunk as base64
        const chunkBase64 = await ReactNativeBlobUtil.fs.readFile(filePath, 'base64', start, end);
        const chunkWordArray = crypto.enc.Base64.parse(chunkBase64);
        
        // Update hash with this chunk
        hashObj = hashObj.update(chunkWordArray);
        
        // Update verification progress
        const progress = Math.floor(((i + 1) / totalChunks) * 100);
        setDownloadProgress(progress);
        setMessages((msgs) => {
          return msgs.map(msg => {
            if (msg.metadata?.downloading && msg.type === 'text') {
              return {
                ...msg,
                text: `Verifying file integrity... ${progress}%`,
              };
            }
            return msg;
          });
        });
      }
      
      // Finalize hash
      const hash = hashObj.finalize();
      return hash.toString(crypto.enc.Hex);
    } catch (error) {
      console.error('Hash calculation error:', error);
      throw new Error(`Hash calculation failed: ${error.message}`);
    }
  }
  
  // Verify file size matches expected size
  const verifyFileSize = async (filePath: string, expectedSize: number): Promise<boolean> => {
    try {
      const stats = await ReactNativeBlobUtil.fs.stat(filePath);
      return stats.size === expectedSize;
    } catch (error) {
      console.error('File size verification error:', error);
      return false;
    }
  }
  
  // Get file metadata (size, hash) from server
  const getFileMetadata = async (url: string): Promise<{size: number, hash?: string}> => {
    try {
      // Try to get file size with HEAD request
      const response = await fetch(url, { method: 'HEAD' });
      const contentLength = response.headers.get('content-length');
      const contentMd5 = response.headers.get('content-md5');
      const etag = response.headers.get('etag')?.replace(/"/g, '');
      
      return {
        size: contentLength ? parseInt(contentLength) : 0,
        hash: contentMd5 || etag
      };
    } catch (error) {
      console.error('Failed to get file metadata:', error);
      return { size: 0 };
    }
  }
  
  const downloadModel = async (url: string) => {
    const modelDir = `${ReactNativeBlobUtil.fs.dirs.DocumentDir}/models`;
    const modelName = url.split('/').pop() || 'model.gguf';
    const modelPath = `${modelDir}/${modelName}`;
    const tempPath = `${modelPath}.partial`;
    
    // Create directory if needed
    if (!(await ReactNativeBlobUtil.fs.exists(modelDir))) {
      await ReactNativeBlobUtil.fs.mkdir(modelDir);
    }
    
    // Check if already downloaded
    if (await ReactNativeBlobUtil.fs.exists(modelPath)) {
      // Verify the file size matches expected
      setDownloadPhase('Verifying existing file...');
      const metadata = await getFileMetadata(url);
      
      if (metadata.size > 0) {
        const sizeMatches = await verifyFileSize(modelPath, metadata.size);
        if (sizeMatches) {
          addSystemMessage('Model already downloaded and verified, loading...');
          return { uri: modelPath };
        } else {
          addSystemMessage('Existing file is incomplete or corrupted. Re-downloading...');
          await ReactNativeBlobUtil.fs.unlink(modelPath);
        }
      } else {
        // If we can't verify, assume it's good
        addSystemMessage('Model already downloaded, loading...');
        return { uri: modelPath };
      }
    }
    
    setDownloading(true);
    setDownloadProgress(0);
    setDownloadSpeed('');
    setDownloadPhase('Preparing download...');
    
    // Add a message to show download progress
    const msgId = addSystemMessage('Preparing download...', { downloading: true });
    
    try {
      // Get file metadata for verification
      const metadata = await getFileMetadata(url);
      if (metadata.size === 0) {
        throw new Error('Could not determine file size');
      }
      
      // Check if we have a partial download to resume
      let startByte = 0;
      if (await ReactNativeBlobUtil.fs.exists(tempPath)) {
        const stats = await ReactNativeBlobUtil.fs.stat(tempPath);
        if (stats.size < metadata.size) {
          startByte = stats.size;
          setDownloadPhase('Resuming download...');
          addSystemMessage(`Resuming download from ${(startByte / 1024 / 1024).toFixed(2)} MB`);
        } else {
          // Partial file is already complete or larger than expected (corrupted?)
          await ReactNativeBlobUtil.fs.unlink(tempPath);
        }
      }
      
      // Prepare for chunked download
      const CHUNK_SIZE = 10 * 1024 * 1024; // 10MB chunks
      const MAX_CONCURRENT = 3; // Maximum concurrent downloads
      const totalSize = metadata.size;
      
      // Calculate chunks
      const chunks = [];
      for (let start = startByte; start < totalSize; start += CHUNK_SIZE) {
        const end = Math.min(start + CHUNK_SIZE - 1, totalSize - 1);
        chunks.push({ start, end, downloaded: false });
      }
      
      setDownloadPhase('Downloading in parallel...');
      
      // Track download progress
      let totalDownloaded = startByte;
      let lastUpdateTime = Date.now();
      let lastDownloadedBytes = 0;
      
      const updateProgress = (additionalBytes: number) => {
        totalDownloaded += additionalBytes;
        const progress = Math.floor((totalDownloaded / totalSize) * 100);
        setDownloadProgress(progress);
        
        // Calculate download speed
        const now = Date.now();
        const timeDiff = now - lastUpdateTime;
        if (timeDiff > 1000) { // Update speed every second
          const bytesPerSec = (totalDownloaded - lastDownloadedBytes) / (timeDiff / 1000);
          const speedMBps = (bytesPerSec / (1024 * 1024)).toFixed(2);
          setDownloadSpeed(`${speedMBps} MB/s`);
          
          // Update message with progress and speed
          setMessages((msgs) => {
            const index = msgs.findIndex((msg) => msg.id === msgId);
            if (index >= 0) {
              return msgs.map((msg, i) => {
                if (msg.type === 'text' && i === index) {
                  return {
                    ...msg,
                    text: `Downloading model... ${progress}% (${speedMBps} MB/s)`,
                  };
                }
                return msg;
              });
            }
            return msgs;
          });
          
          lastUpdateTime = now;
          lastDownloadedBytes = totalDownloaded;
        }
      };
      
      // Download chunks in parallel with concurrency limit
      for (let i = 0; i < chunks.length; i += MAX_CONCURRENT) {
        const chunkPromises = [];
        
        for (let j = 0; j < MAX_CONCURRENT && i + j < chunks.length; j++) {
          const chunk = chunks[i + j];
          const chunkPath = `${tempPath}.part${i + j}`;
          
          chunkPromises.push(
            (async () => {
              await ReactNativeBlobUtil.config({
                fileCache: true,
                path: chunkPath,
                timeout: 60000,
                IOSBackgroundTask: true,
                followRedirect: true,
                trusty: true,
                bufferSize: 1 * 1024 * 1024 // 1MB buffer for chunks
              })
                .fetch('GET', url, {
                  'Range': `bytes=${chunk.start}-${chunk.end}`
                })
                .progress((received) => {
                  updateProgress(received - (chunk.lastReceived || 0));
                  chunk.lastReceived = received;
                });
              
              chunk.downloaded = true;
              return { index: i + j, path: chunkPath };
            })()
          );
        }
        
        // Wait for current batch to complete
        const results = await Promise.all(chunkPromises);
        
        // If this is the first batch and we're resuming, append to existing file
        // Otherwise create/overwrite the file with the first chunk
        if (i === 0) {
          if (startByte > 0) {
            // File exists and we're appending
            for (const result of results) {
              const chunkData = await ReactNativeBlobUtil.fs.readFile(result.path, 'base64');
              await ReactNativeBlobUtil.fs.appendFile(tempPath, chunkData, 'base64');
              await ReactNativeBlobUtil.fs.unlink(result.path);
            }
          } else {
            // Create new file with first chunk
            const firstChunk = results.find(r => r.index === 0);
            if (firstChunk) {
              await ReactNativeBlobUtil.fs.mv(firstChunk.path, tempPath);
              
              // Append other chunks from this batch
              for (const result of results) {
                if (result.index !== 0) {
                  const chunkData = await ReactNativeBlobUtil.fs.readFile(result.path, 'base64');
                  await ReactNativeBlobUtil.fs.appendFile(tempPath, chunkData, 'base64');
                  await ReactNativeBlobUtil.fs.unlink(result.path);
                }
              }
            }
          }
        } else {
          // Append all chunks from this batch
          for (const result of results) {
            const chunkData = await ReactNativeBlobUtil.fs.readFile(result.path, 'base64');
            await ReactNativeBlobUtil.fs.appendFile(tempPath, chunkData, 'base64');
            await ReactNativeBlobUtil.fs.unlink(result.path);
          }
        }
      }
      
      // Verify downloaded file
      setDownloadPhase('Verifying download...');
      setDownloadProgress(0);
      setMessages((msgs) => {
        return msgs.map(msg => {
          if (msg.id === msgId && msg.type === 'text') {
            return {
              ...msg,
              text: 'Verifying download...',
            };
          }
          return msg;
        });
      });
      
      // Verify file size
      const downloadedSize = (await ReactNativeBlobUtil.fs.stat(tempPath)).size;
      if (downloadedSize !== totalSize) {
        throw new Error(`File size mismatch: expected ${totalSize}, got ${downloadedSize}`);
      }
      
      // If we have a hash from the server, verify it
      if (metadata.hash) {
        const downloadedHash = await calculateFileHash(tempPath);
        if (downloadedHash.toLowerCase() !== metadata.hash.toLowerCase()) {
          throw new Error('File hash verification failed');
        }
      }
      
      // Move temp file to final location
      await ReactNativeBlobUtil.fs.mv(tempPath, modelPath);
      
      setDownloading(false);
      addSystemMessage('Download complete and verified!');
      return { uri: modelPath };
    } catch (error) {
      setDownloading(false);
      addSystemMessage(`Download failed: ${error.message}`);
      throw error;
    }
  }

  const handleDownloadDefaultModel = async () => {
    try {
      const modelFile = await downloadModel(DEFAULT_MODEL_URL)
      lastModelPathRef.current = modelFile.uri
      handleInitContext(modelFile, null)
    } catch (error) {
      console.error('Failed to download model:', error)
    }
  }

  // // Example: Get model info without initializing context
  const getModelInfo = async (model: string) => {
    const t0 = Date.now()
    const info = await loadLlamaModelInfo(model)
    // console.log(`Model info (took ${Date.now() - t0}ms): `, info)
  }
  
  // Handle app state changes
  useEffect(() => {
    const subscription = AppState.addEventListener('change', nextAppState => {
      if (nextAppState === 'background' && context) {
        // App is going to background, release context to save memory
        addSystemMessage('App going to background, releasing model to save memory...')
        handleReleaseContext()
      } else if (nextAppState === 'active' && !context && lastModelPathRef.current) {
        // App is coming to foreground, ask to reload the model
        Alert.alert(
          'Reload Model',
          'Would you like to reload the model?',
          [
            { 
              text: 'Yes', 
              onPress: () => {
                const modelFile = { uri: lastModelPathRef.current as string }
                handleInitContext(modelFile, null)
              } 
            },
            { text: 'No', style: 'cancel' }
          ]
        )
      }
    })
    
    return () => {
      subscription.remove()
    }
  }, [context])
  
  // Show welcome message on first load
  useEffect(() => {
    if (messages.length === 0) {
      addSystemMessage(
        'Welcome to the LLM Chat App!\n\n' +
        'To get started, you can:\n' +
        '1. Download our recommended model (1.1GB)\n' +
        '2. Pick a model from your device\n\n' +
        'Use the button below to download the recommended model, or tap the attachment icon to select a model from your device.'
      )
    }
  }, [])
  
  // Check for restored conversation when context is loaded
  useEffect(() => {
    if (context) {
      // If we have a context but no messages, check if there's a restored conversation
      if (messages.length <= 1) { // Only welcome message or empty
        addSystemMessage('Checking for saved conversation...')
        
        // This will trigger the native code to check for saved conversations
        context.saveSession(`${dirs.DocumentDir}/dummy_session.bin`)
          .then(() => {
            // Wait a moment for any restored messages to appear
            setTimeout(() => {
              if (messages.length <= 2) { // Still only welcome + checking message
                addSystemMessage('No saved conversation found or restoration failed.')
              }
            }, 500)
          })
          .catch(err => {
            console.error('Error checking for saved conversation:', err)
          })
      }
    }
  }, [context])

  const handleInitContext = async (
    file: DocumentPickerResponse,
    loraFile: DocumentPickerResponse | null,
  ) => {
    await handleReleaseContext()
    await getModelInfo(file.uri)
    const msgId = addSystemMessage('Initializing context...')
    const t0 = Date.now()
    initLlama(
      {
        model: file.uri,
        use_mlock: true,
        lora_list: loraFile ? [{ path: loraFile.uri, scaled: 1.0 }] : undefined, // Or lora: loraFile?.uri,

        // Set maximum context window size to 1024 tokens
        n_ctx: 1024,

        // If use deepseek r1 distill
        reasoning_format: 'deepseek',

        // Currently only for iOS
        n_gpu_layers: Platform.OS === 'ios' ? 99 : 0,
        // no_gpu_devices: true, // (iOS only)
      },
      (progress) => {
        setMessages((msgs) => {
          const index = msgs.findIndex((msg) => msg.id === msgId)
          if (index >= 0) {
            return msgs.map((msg, i) => {
              if (msg.type == 'text' && i === index) {
                return {
                  ...msg,
                  text: `Initializing context... ${progress}%`,
                }
              }
              return msg
            })
          }
          return msgs
        })
      },
    )
      .then((ctx) => {
        const t1 = Date.now()
        setContext(ctx)
        addSystemMessage(
          `Context initialized!\n\nLoad time: ${t1 - t0}ms\nGPU: ${
            ctx.gpu ? 'YES' : 'NO'
          } (${ctx.reasonNoGPU})\nChat Template: ${
            ctx.model.chatTemplates.llamaChat ? 'YES' : 'NO'
          }\n\n` +
            'You can use the following commands:\n\n' +
            '- /info: to get the model info\n' +
            '- /bench: to benchmark the model\n' +
            '- /release: release the context\n' +
            '- /stop: stop the current completion\n' +
            '- /reset: reset the conversation' +
            '- /save-session: save the session tokens\n' +
            '- /load-session: load the session tokens',
        )
      })
      .catch((err) => {
        addSystemMessage(`Context initialization failed: ${err.message}`)
      })
  }

  const copyFileIfNeeded = async (
    type = 'model',
    file: DocumentPickerResponse,
  ) => {
    if (Platform.OS === 'android' && file.uri.startsWith('content://')) {
      const dir = `${ReactNativeBlobUtil.fs.dirs.CacheDir}/${type}s`
      const filepath = `${dir}/${file.uri.split('/').pop() || type}.gguf`

      if (!(await ReactNativeBlobUtil.fs.isDir(dir)))
        await ReactNativeBlobUtil.fs.mkdir(dir)

      if (await ReactNativeBlobUtil.fs.exists(filepath))
        return { uri: filepath } as DocumentPickerResponse

      await ReactNativeBlobUtil.fs.unlink(dir) // Clean up old files in models

      addSystemMessage(`Copying ${type} to internal storage...`)
      await ReactNativeBlobUtil.MediaCollection.copyToInternal(
        file.uri,
        filepath,
      )
      addSystemMessage(`${type} copied!`)
      return { uri: filepath } as DocumentPickerResponse
    }
    return file
  }

  const pickLora = async () => {
    let loraFile
    const loraRes = await DocumentPicker.pick({
      type: Platform.OS === 'ios' ? 'public.data' : 'application/octet-stream',
    }).catch((e) => console.log('No lora file picked, error: ', e.message))
    if (loraRes?.[0]) loraFile = await copyFileIfNeeded('lora', loraRes[0])
    return loraFile
  }
  const handlePickModelFromDirectory = async () => {                                                                                    
    const modelsDir = `${ReactNativeBlobUtil.fs.dirs.DocumentDir}/models`                                                               
    const exists = await ReactNativeBlobUtil.fs.exists(modelsDir)                                                                       
                                                                                                                                        
    if (!exists) {                                                                                                                      
      addSystemMessage("Models directory doesn't exist. Creating it now.")                                                              
      await ReactNativeBlobUtil.fs.mkdir(modelsDir)                                                                                     
      addSystemMessage(`Please add model files to: ${modelsDir}`)                                                                       
      return                                                                                                                            
    }                                                                                                                                   
                                                                                                                                        
    const files = await ReactNativeBlobUtil.fs.ls(modelsDir)                                                                            
    if (files.length === 0) {                                                                                                           
      addSystemMessage(`No model files found in: ${modelsDir}`)                                                                         
      return                                                                                                                            
    }                                                                                                                                   
                                                                                                                                        
    // If there's only one file, use it directly                                                                                        
    if (files.length === 1) {                                                                                                           
      const modelFile = { uri: `${modelsDir}/${files[0]}` }                                                                             
      handleInitContext(modelFile, null)                                                                                                
      return                                                                                                                            
    }                                                                                                                                   
                                                                                                                                        
    // Otherwise, show a picker for multiple files                                                                                      
    Alert.alert(                                                                                                                        
      'Select a Model',                                                                                                                 
      'Choose a model file to load:',                                                                                                   
      files.map(file => ({                                                                                                              
        text: file,                                                                                                                     
        onPress: async () => {                                                                                                          
          const modelFile = { uri: `${modelsDir}/${file}` }                                                                             
          handleInitContext(modelFile, null)                                                                                            
        }                                                                                                                               
      })),                                                                                                                              
      { cancelable: true }                                                                                                              
    )                                                                                                                                   
  }                                                                                                                                     
                                                                                                                                        
  const handlePickModel = async () => {                                                                                                 
    // First try to pick from the models directory                                                                                      
    const modelsDir = `${ReactNativeBlobUtil.fs.dirs.DocumentDir}/models`                                                               
    const exists = await ReactNativeBlobUtil.fs.exists(modelsDir)                                                                       
                                                                                                                                        
    if (exists) {                                                                                                                       
      // Check if there are models in the directory                                                                                     
      const files = await ReactNativeBlobUtil.fs.ls(modelsDir)                                                                          
      if (files.length > 0) {                                                                                                           
        // If models exist, ask user whether to use directory or document picker                                                        
        Alert.alert(                                                                                                                    
          'Choose Model Source',                                                                                                        
          'Where would you like to get the model from?',                                                                                
          [                                                                                                                             
            {                                                                                                                           
              text: 'Models Directory',                                                                                                 
              onPress: handlePickModelFromDirectory                                                                                     
            },                                                                                                                          
            {                                                                                                                           
              text: 'Document Picker',                                                                                                  
              onPress: async () => {                                                                                                    
                // Original document picker flow                                                                                        
                const modelRes = await DocumentPicker.pick({                                                                            
                  type: Platform.OS === 'ios' ? 'public.data' : 'application/octet-stream',                                             
                }).catch((e) => console.log('No model file picked, error: ', e.message))                                                
                if (!modelRes?.[0]) return                                                                                              
                const modelFile = await copyFileIfNeeded('model', modelRes?.[0])                                                        
                                                                                                                                        
                let loraFile: any = null                                                                                                
                // Example: Apply lora adapter (Currently only select one lora file) (Uncomment to use)                                 
                // loraFile = await pickLora()                                                                                          
                loraFile = null                                                                                                         
                                                                                                                                        
                handleInitContext(modelFile, loraFile)                                                                                  
              }                                                                                                                         
            }                                                                                                                           
          ],                                                                                                                            
          { cancelable: true }                                                                                                          
        )                                                                                                                               
        return                                                                                                                          
      }                                                                                                                                 
    }                                                                                                                                   
                                                                                                                                        
    // If no models directory or it's empty, fall back to document picker                                                               
    const modelRes = await DocumentPicker.pick({                                                                                        
      type: Platform.OS === 'ios' ? 'public.data' : 'application/octet-stream',                                                         
    }).catch((e) => console.log('No model file picked, error: ', e.message))                                                            
    if (!modelRes?.[0]) return                                                                                                          
    const modelFile = await copyFileIfNeeded('model', modelRes?.[0])                                                                    
                                                                                                                                        
    let loraFile: any = null                                                                                                            
    // Example: Apply lora adapter (Currently only select one lora file) (Uncomment to use)                                             
    // loraFile = await pickLora()                                                                                                      
    loraFile = null                                                                                                                     
                                                                                                                                        
    handleInitContext(modelFile, loraFile)                                                                                              
  }                      
  
  const handleSendPress = async (message: MessageType.PartialText) => {
    if (context) {
      switch (message.text) {
        case '/info':
          addSystemMessage(
            `// Model Info\n${json5.stringify(context.model, null, 2)}`,
            { copyable: true },
          )
          return
        case '/bench':
          addSystemMessage('Heating up the model...')
          const t0 = Date.now()
          await context.bench(8, 4, 1, 1)
          const tHeat = Date.now() - t0
          if (tHeat > 1e4) {
            addSystemMessage('Heat up time is too long, please try again.')
            return
          }
          addSystemMessage(`Heat up time: ${tHeat}ms`)

          addSystemMessage('Benchmarking the model...')
          const {
            modelDesc,
            modelSize,
            modelNParams,
            ppAvg,
            ppStd,
            tgAvg,
            tgStd,
          } = await context.bench(512, 128, 1, 3)

          const size = `${(modelSize / 1024.0 / 1024.0 / 1024.0).toFixed(
            2,
          )} GiB`
          const nParams = `${(modelNParams / 1e9).toFixed(2)}B`
          const md =
            '| model | size | params | test | t/s |\n' +
            '| --- | --- | --- | --- | --- |\n' +
            `| ${modelDesc} | ${size} | ${nParams} | pp 512 | ${ppAvg.toFixed(
              2,
            )} ± ${ppStd.toFixed(2)} |\n` +
            `| ${modelDesc} | ${size} | ${nParams} | tg 128 | ${tgAvg.toFixed(
              2,
            )} ± ${tgStd.toFixed(2)}`
          addSystemMessage(md, { copyable: true })
          return
        case '/release':
          await handleReleaseContext()
          return
        case '/stop':
          if (inferencing) context.stopCompletion()
          return
        case '/reset':
          conversationIdRef.current = randId()
          addSystemMessage('Conversation reset!')
          return
        case '/save-session':
          context
            .saveSession(`${dirs.DocumentDir}/llama-session.bin`)
            .then((tokensSaved) => {
              console.log('Session tokens saved:', tokensSaved)
              addSystemMessage(`Session saved! ${tokensSaved} tokens saved.`)
            })
            .catch((e) => {
              console.log('Session save failed:', e)
              addSystemMessage(`Session save failed: ${e.message}`)
            })
          return
        case '/load-session':
          context
            .loadSession(`${dirs.DocumentDir}/llama-session.bin`)
            .then((details) => {
              console.log('Session loaded:', details)
              addSystemMessage(
                `Session loaded! ${details.tokens_loaded} tokens loaded.`,
              )
            })
            .catch((e) => {
              console.log('Session load failed:', e)
              addSystemMessage(`Session load failed: ${e.message}`)
            })
          return
        case '/lora':
          pickLora()
            .then((loraFile) => {
              if (loraFile) context.applyLoraAdapters([{ path: loraFile.uri }])
            })
            .then(() => context.getLoadedLoraAdapters())
            .then((loraList) =>
              addSystemMessage(
                `Loaded lora adapters: ${JSON.stringify(loraList)}`,
              ),
            )
          return
        case '/remove-lora':
          context.removeLoraAdapters().then(() => {
            addSystemMessage('Lora adapters removed!')
          })
          return
        case '/lora-list':
          context.getLoadedLoraAdapters().then((loraList) => {
            addSystemMessage(
              `Loaded lora adapters: ${JSON.stringify(loraList)}`,
            )
          })
          return
      }
    }
    const textMessage: MessageType.Text = {
      author: user,
      createdAt: Date.now(),
      id: randId(),
      text: message.text,
      type: 'text',
      metadata: {
        contextId: context?.id,
        conversationId: conversationIdRef.current,
      },
    }

    const id = randId()
    const createdAt = Date.now()
    const msgs = [
      systemMessage,
      ...[...messages]
        .reverse()
        .map((msg) => {
          if (
            !msg.metadata?.system &&
            msg.metadata?.conversationId === conversationIdRef.current &&
            msg.metadata?.contextId === context?.id &&
            msg.type === 'text'
          ) {
            return {
              role: msg.author.id === systemId ? 'assistant' : 'user',
              content: msg.text,
            }
          }
          return { role: '', content: '' }
        })
        .filter((msg) => msg.role),
      { role: 'user', content: message.text },
    ]
    addMessage(textMessage)
    setInferencing(true)

    let responseFormat
    {
      // Test JSON Schema
      responseFormat = {
        type: 'json_schema',
        json_schema: {
          schema: {
            oneOf: [
              {
                type: 'object',
                properties: {
                  function: { const: 'create_event' },
                  arguments: {
                    type: 'object',
                    properties: {
                      title: { type: 'string' },
                      date: { type: 'string' },
                      time: { type: 'string' },
                    },
                    required: ['title', 'date'],
                  },
                },
                required: ['function', 'arguments'],
              },
              {
                type: 'object',
                properties: {
                  function: { const: 'image_search' },
                  arguments: {
                    type: 'object',
                    properties: {
                      query: { type: 'string' },
                    },
                    required: ['query'],
                  },
                },
                required: ['function', 'arguments'],
              },
            ],
          },
        },
      }
      // Comment to test:
      responseFormat = undefined
    }

    let grammar
    {
      // Test grammar (It will override responseFormat)
      grammar = testGbnf
      // Comment to test:
      grammar = undefined
    }

    let jinjaParams: any = {}
    // Test jinja & tools
    {
      jinjaParams = {
        jinja: true,
        response_format: responseFormat,
        tool_choice: 'auto',
        tools: [
          {
            type: 'function',
            function: {
              name: 'ipython',
              description:
                'Runs code in an ipython interpreter and returns the result of the execution after 60 seconds.',
              parameters: {
                type: 'object',
                properties: {
                  code: {
                    type: 'string',
                    description: 'The code to run in the ipython interpreter.',
                  },
                },
                required: ['code'],
              },
            },
          },
        ],
      }
      // Comment to test:
      jinjaParams = { jinja: true }
    }

    // Test area
    {
      // Test tokenize
      const formatted =
        (await context?.getFormattedChat(msgs, null, jinjaParams)) || ''
      const prompt =
        typeof formatted === 'string' ? formatted : formatted.prompt
      const t0 = Date.now()
      const { tokens } = (await context?.tokenize(prompt)) || {}
      const t1 = Date.now()

      // console.log(
      //   'Formatted:',
      //   formatted,
      //   '\nTokenize:',
      //   tokens,
      //   `(${tokens?.length} tokens, ${t1 - t0}ms})`,
      // )

      // Test embedding
      // await context?.embedding(prompt).then((result) => {
      //   console.log('Embedding:', result)
      // })

      // Test detokenize
      // await context?.detokenize(tokens).then((result) => {
      //   console.log('Detokenize:', result)
      // })
    }

    context
      ?.completion(
        {
          messages: msgs,
          n_predict: 128, // Limit maximum output token length to 128

          response_format: responseFormat,
          grammar,
          ...jinjaParams,

          seed: -1,
          n_probs: 0,

          // Sampling params
          top_k: 40,
          top_p: 0.5,
          min_p: 0.05,
          xtc_probability: 0.5,
          xtc_threshold: 0.1,
          typical_p: 1.0,
          temperature: 0.7,
          penalty_last_n: 64,
          penalty_repeat: 1.0,
          penalty_freq: 0.0,
          penalty_present: 0.0,
          dry_multiplier: 0,
          dry_base: 1.75,
          dry_allowed_length: 2,
          dry_penalty_last_n: -1,
          dry_sequence_breakers: ['\n', ':', '"', '*'],
          mirostat: 0,
          mirostat_tau: 5,
          mirostat_eta: 0.1,
          ignore_eos: false,
          stop: [
            '</s>',
            '<|end|>',
            '<|eot_id|>',
            '<|end_of_text|>',
            '<|im_end|>',
            '<|EOT|>',
            '<|END_OF_TURN_TOKEN|>',
            '<|end_of_turn|>',
            '<|endoftext|>',
            '<end_of_turn>',
            '<eos>',
            '<｜end▁of▁sentence｜>',
          ],
          // n_threads: 4,
          // logit_bias: [[15043,1.0]],
        },
        (data) => {
          const { token } = data
          setMessages((msgs) => {
            const index = msgs.findIndex((msg) => msg.id === id)
            if (index >= 0) {
              return msgs.map((msg, i) => {
                if (msg.type == 'text' && i === index) {
                  return {
                    ...msg,
                    text: (msg.text + token).replace(/^\s+/, ''),
                  }
                }
                return msg
              })
            }
            return [
              {
                author: system,
                createdAt,
                id,
                text: token,
                type: 'text',
                metadata: {
                  contextId: context?.id,
                  conversationId: conversationIdRef.current,
                },
              },
              ...msgs,
            ]
          })
        },
      )
      .then((completionResult) => {
        // console.log('completionResult: ', completionResult)
        const timings = `${completionResult.timings.predicted_per_token_ms.toFixed()}ms per token, ${completionResult.timings.predicted_per_second.toFixed(
          2,
        )} tokens per second`
        setMessages((msgs) => {
          const index = msgs.findIndex((msg) => msg.id === id)
          if (index >= 0) {
            return msgs.map((msg, i) => {
              if (msg.type == 'text' && i === index) {
                return {
                  ...msg,
                  metadata: {
                    ...msg.metadata,
                    timings,
                  },
                }
              }
              return msg
            })
          }
          return msgs
        })
        setInferencing(false)
      })
      .catch((e) => {
        console.log('completion error: ', e)
        setInferencing(false)
        addSystemMessage(`Completion failed: ${e.message}`)
      })
  }

  return (
    <SafeAreaProvider>
      <Chat
        renderBubble={renderBubble}
        theme={customTheme}
        messages={messages}
        onSendPress={handleSendPress}
        user={user}
        onAttachmentPress={!context ? 
          () => {
            if (downloading) {
              Alert.alert('Download in Progress', 'Please wait for the current download to complete.')
              return
            }
            
            Alert.alert(
              'Choose Model Source',
              'Where would you like to get the model from?',
              [
                {
                  text: 'Download Recommended Model',
                  onPress: handleDownloadDefaultModel
                },
                {
                  text: 'Pick from Device',
                  onPress: handlePickModel
                },
                {
                  text: 'Cancel',
                  style: 'cancel'
                }
              ]
            )
          } : undefined
        }
        textInputProps={{
          editable: !!context && !downloading,
          placeholder: downloading 
            ? `${downloadPhase} ${downloadProgress}% ${downloadSpeed}` 
            : !context
              ? 'Press the file icon to get a model'
              : 'Type your message here',
        }}
      />
    </SafeAreaProvider>
  )
}
