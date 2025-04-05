import { NativeEventEmitter, DeviceEventEmitter, Platform } from 'react-native'
import type { DeviceEventEmitterStatic } from 'react-native'
import RNLlama from './NativeRNLlama'
import type {
  NativeContextParams,
  NativeLlamaContext,
  NativeCompletionParams,
  NativeCompletionTokenProb,
  NativeCompletionResult,
  NativeTokenizeResult,
  NativeEmbeddingResult,
  NativeSessionLoadResult,
  NativeEmbeddingParams,
  NativeCompletionTokenProbItem,
  NativeCompletionResultTimings,
  JinjaFormattedChatResult,
} from './NativeRNLlama'
import type {
  SchemaGrammarConverterPropOrder,
  SchemaGrammarConverterBuiltinRule,
} from './grammar'
import { SchemaGrammarConverter, convertJsonSchemaToGrammar } from './grammar'
import type { RNLlamaMessagePart, RNLlamaOAICompatibleMessage } from './chat'
import { formatChat } from './chat'

export type {
  NativeContextParams,
  NativeLlamaContext,
  NativeCompletionParams,
  NativeCompletionTokenProb,
  NativeCompletionResult,
  NativeTokenizeResult,
  NativeEmbeddingResult,
  NativeSessionLoadResult,
  NativeEmbeddingParams,
  NativeCompletionTokenProbItem,
  NativeCompletionResultTimings,
  RNLlamaMessagePart,
  RNLlamaOAICompatibleMessage,
  JinjaFormattedChatResult,

  // Deprecated
  SchemaGrammarConverterPropOrder,
  SchemaGrammarConverterBuiltinRule,
}

export { SchemaGrammarConverter, convertJsonSchemaToGrammar }

const EVENT_ON_INIT_CONTEXT_PROGRESS = '@RNLlama_onInitContextProgress'
const EVENT_ON_TOKEN = '@RNLlama_onToken'
const EVENT_ON_NATIVE_LOG = '@RNLlama_onNativeLog'
const EVENT_ON_CONVERSATION_RESTORED = '@RNLlama_onConversationRestored'


let EventEmitter: NativeEventEmitter | DeviceEventEmitterStatic
if (Platform.OS === 'ios') {
  // @ts-ignore
  EventEmitter = new NativeEventEmitter(RNLlama)
}
if (Platform.OS === 'android') {
  EventEmitter = DeviceEventEmitter
}

const logListeners: Array<(level: string, text: string) => void> = []

// @ts-ignore
if (EventEmitter) {
  EventEmitter.addListener(
    EVENT_ON_NATIVE_LOG,
    (evt: { level: string; text: string }) => {
      logListeners.forEach((listener) => listener(evt.level, evt.text))
    },
  )
  RNLlama?.toggleNativeLog?.(false) // Trigger unset to use default log callback
}

export type TokenData = {
  token: string
  completion_probabilities?: Array<NativeCompletionTokenProb>
}

type TokenNativeEvent = {
  contextId: number
  tokenResult: TokenData
}

export type ConversationRestoredEvent = {
  contextId: number
  messages: RNLlamaOAICompatibleMessage[]
  timestamp?: number
  success?: boolean
  tokenCount?: number
}

export type ContextParams = Omit<
  NativeContextParams,
  'cache_type_k' | 'cache_type_v' | 'pooling_type'
> & {
  contextId?: number
  cache_type_k?:
    | 'f16'
    | 'f32'
    | 'q8_0'
    | 'q4_0'
    | 'q4_1'
    | 'iq4_nl'
    | 'q5_0'
    | 'q5_1'
  cache_type_v?:
    | 'f16'
    | 'f32'
    | 'q8_0'
    | 'q4_0'
    | 'q4_1'
    | 'iq4_nl'
    | 'q5_0'
    | 'q5_1'
  pooling_type?: 'none' | 'mean' | 'cls' | 'last' | 'rank'
}

export type EmbeddingParams = NativeEmbeddingParams

export type CompletionResponseFormat = {
  type: 'text' | 'json_object' | 'json_schema'
  json_schema?: {
    strict?: boolean
    schema: object
  }
  schema?: object // for json_object type
}

export type CompletionBaseParams = {
  prompt?: string
  messages?: RNLlamaOAICompatibleMessage[]
  chatTemplate?: string // deprecated
  chat_template?: string
  jinja?: boolean
  tools?: object
  parallel_tool_calls?: object
  tool_choice?: string
  response_format?: CompletionResponseFormat
}
export type CompletionParams = Omit<
  NativeCompletionParams,
  'emit_partial_completion' | 'prompt'
> &
  CompletionBaseParams

export type BenchResult = {
  modelDesc: string
  modelSize: number
  modelNParams: number
  ppAvg: number
  ppStd: number
  tgAvg: number
  tgStd: number
}

const getJsonSchema = (responseFormat?: CompletionResponseFormat) => {
  if (responseFormat?.type === 'json_schema') {
    return responseFormat.json_schema?.schema
  }
  if (responseFormat?.type === 'json_object') {
    return responseFormat.schema || {}
  }
  return null
}

export class LlamaContext {
  id: number

  gpu: boolean = false

  reasonNoGPU: string = ''

  model: NativeLlamaContext['model']

  constructor({ contextId, gpu, reasonNoGPU, model }: NativeLlamaContext) {
    this.id = contextId
    this.gpu = gpu
    this.reasonNoGPU = reasonNoGPU
    this.model = model
  }

  /**
   * Load cached prompt & completion state from a file.
   */
  async loadSession(filepath: string): Promise<NativeSessionLoadResult> {
    // DEBUG: Log parameters before loading session
    let path = filepath
    if (path.startsWith('file://')) path = path.slice(7)
    console.log(`[LlamaContext ${this.id}] loadSession: Attempting to load from path: ${path}`);
    return RNLlama.loadSession(this.id, path)
      .then(result => {
        console.log(`[LlamaContext ${this.id}] loadSession: Native loadSession result:`, JSON.stringify(result, null, 2));
        return result;
      });
  }

  /**
   * Save current cached prompt & completion state to a file.
   */
  async saveSession(
    filepath: string,
    options?: { tokenSize: number },
  ): Promise<number> {
    // DEBUG: Log parameters before saving session
    let path = filepath
    if (path.startsWith('file://')) path = path.slice(7)
    console.log(`[LlamaContext ${this.id}] saveSession: Attempting to save to path: ${path}, options:`, JSON.stringify(options, null, 2));
    return RNLlama.saveSession(this.id, filepath, options?.tokenSize || -1)
      .then(result => {
        console.log(`[LlamaContext ${this.id}] saveSession: Native saveSession result (tokens saved):`, result);
        return result;
      });
  }

  isLlamaChatSupported(): boolean {
    return !!this.model.chatTemplates.llamaChat
  }

  isJinjaSupported(): boolean {
    const { minja } = this.model.chatTemplates
    return !!minja?.toolUse || !!minja?.default
  }

  async getFormattedChat(
    messages: RNLlamaOAICompatibleMessage[],
    template?: string | null,
    params?: {
      jinja?: boolean
      response_format?: CompletionResponseFormat
      tools?: object
      parallel_tool_calls?: object
      tool_choice?: string
    },
  ): Promise<JinjaFormattedChatResult | string> {
    const chat = formatChat(messages)

    // DEBUG: Log formatted chat array input and other formatting params
    console.log(`[LlamaContext ${this.id}] getFormattedChat: Input chat array:`, JSON.stringify(chat, null,2));
    console.log(`[LlamaContext ${this.id}] getFormattedChat: Formatting params:`, JSON.stringify({ template, params }, null, 2));


    const useJinja = this.isJinjaSupported() && params?.jinja
    let tmpl = this.isLlamaChatSupported() || useJinja ? undefined : 'chatml'
    if (template) tmpl = template // Force replace if provided
    const jsonSchema = getJsonSchema(params?.response_format)


    // return RNLlama.getFormattedChat(this.id, JSON.stringify(chat), tmpl, {
    //   jinja: useJinja,
    //   json_schema: jsonSchema ? JSON.stringify(jsonSchema) : undefined,
    //   tools: params?.tools ? JSON.stringify(params.tools) : undefined,
    //   parallel_tool_calls: params?.parallel_tool_calls
    //     ? JSON.stringify(params.parallel_tool_calls)
    //     : undefined,
    //   tool_choice: params?.tool_choice,
    // })

    // DEBUG: Log parameters being sent to native getFormattedChat
    const nativeParams = {
      jinja: useJinja,
      json_schema: jsonSchema ? JSON.stringify(jsonSchema) : undefined,
      tools: params?.tools ? JSON.stringify(params.tools) : undefined,
      parallel_tool_calls: params?.parallel_tool_calls
        ? JSON.stringify(params.parallel_tool_calls)
        : undefined,
      tool_choice: params?.tool_choice,
    };
    console.log(`[LlamaContext ${this.id}] getFormattedChat: Calling native with chat (stringified): ${JSON.stringify(chat)}, template: ${tmpl}, params:`, JSON.stringify(nativeParams, null, 2));

    const result = await RNLlama.getFormattedChat(this.id, JSON.stringify(chat), tmpl, nativeParams)
    console.log(`[LlamaContext ${this.id}] getFormattedChat: Output from native:`, JSON.stringify(result, null, 2));
    return result;
  }

  async completion(
    params: CompletionParams,
    callback?: (data: TokenData) => void,
  ): Promise<NativeCompletionResult> {
    // DEBUG: Log raw parameters received by completion function
    console.log(`[LlamaContext ${this.id}] completion: Raw input params:`, JSON.stringify(params, null, 2));

    const nativeParams = {
      ...params,
      prompt: params.prompt || '',
      emit_partial_completion: !!callback,
    }

    // DEBUG: Log if processing messages or using direct prompt
    if (params.messages) {
      // messages always win
      const formattedResult = await this.getFormattedChat(
        params.messages,
        params.chat_template || params.chatTemplate,
        {
          jinja: params.jinja,
          tools: params.tools,
          parallel_tool_calls: params.parallel_tool_calls,
          tool_choice: params.tool_choice,
        },
      )
      // DEBUG: Log the result from getFormattedChat
      console.log(`[LlamaContext ${this.id}] completion: Result from getFormattedChat:`, JSON.stringify(formattedResult, null, 2));
      if (typeof formattedResult === 'string') {
        // If we got a string prompt, remove original messages                                                 
        delete nativeParams.messages; 
        nativeParams.prompt = formattedResult || ''
      } else {
        nativeParams.prompt = formattedResult.prompt || ''
        if (typeof formattedResult.chat_format === 'number')
          nativeParams.chat_format = formattedResult.chat_format
        if (formattedResult.grammar)
          nativeParams.grammar = formattedResult.grammar
        if (typeof formattedResult.grammar_lazy === 'boolean')
          nativeParams.grammar_lazy = formattedResult.grammar_lazy
        if (formattedResult.grammar_triggers)
          nativeParams.grammar_triggers = formattedResult.grammar_triggers
        if (formattedResult.preserved_tokens)
          nativeParams.preserved_tokens = formattedResult.preserved_tokens
        if (formattedResult.additional_stops) {
          if (!nativeParams.stop) nativeParams.stop = []
          nativeParams.stop.push(...formattedResult.additional_stops)
          // If we got a structured result, remove original messages                                           
          delete nativeParams.messages; 
        }
      }
    } else {
      console.log(`[LlamaContext ${this.id}] completion: Using direct prompt:`, params.prompt);
      nativeParams.prompt = params.prompt || ''
    }

    // DEBUG: Log if applying JSON schema from response_format
    if (nativeParams.response_format && !nativeParams.grammar) {
      const jsonSchema = getJsonSchema(params.response_format)
      if (jsonSchema) {
        console.log(`[LlamaContext ${this.id}] completion: Applying JSON schema from response_format`);
        nativeParams.json_schema = JSON.stringify(jsonSchema)
      }
    }
    console.log(`[LlamaContext ${this.id}] Calling RNLlama.completion with params:`,JSON.stringify(nativeParams, null, 2));

    let tokenListener: any =
      callback &&
      EventEmitter.addListener(EVENT_ON_TOKEN, (evt: TokenNativeEvent) => {
        const { contextId, tokenResult } = evt
        if (contextId !== this.id) return
        callback(tokenResult)
      })

    if (!nativeParams.prompt) throw new Error('Prompt is required')

    const promise = RNLlama.completion(this.id, nativeParams)
    return promise
      .then((completionResult) => {
        tokenListener?.remove()
        tokenListener = null
        return completionResult
      })
      .catch((err: any) => {
        tokenListener?.remove()
        tokenListener = null
        throw err
      })
  }

  stopCompletion(): Promise<void> {
    return RNLlama.stopCompletion(this.id)
  }

  tokenize(text: string): Promise<NativeTokenizeResult> {
    return RNLlama.tokenize(this.id, text)
  }

  detokenize(tokens: number[]): Promise<string> {
    return RNLlama.detokenize(this.id, tokens)
  }

  embedding(
    text: string,
    params?: EmbeddingParams,
  ): Promise<NativeEmbeddingResult> {
    return RNLlama.embedding(this.id, text, params || {})
  }

  async bench(
    pp: number,
    tg: number,
    pl: number,
    nr: number,
  ): Promise<BenchResult> {
    const result = await RNLlama.bench(this.id, pp, tg, pl, nr)
    const [modelDesc, modelSize, modelNParams, ppAvg, ppStd, tgAvg, tgStd] =
      JSON.parse(result)
    return {
      modelDesc,
      modelSize,
      modelNParams,
      ppAvg,
      ppStd,
      tgAvg,
      tgStd,
    }
  }

  async applyLoraAdapters(
    loraList: Array<{ path: string; scaled?: number }>,
  ): Promise<void> {
    let loraAdapters: Array<{ path: string; scaled?: number }> = []
    if (loraList)
      loraAdapters = loraList.map((l) => ({
        path: l.path.replace(/file:\/\//, ''),
        scaled: l.scaled,
      }))
    return RNLlama.applyLoraAdapters(this.id, loraAdapters)
  }

  async removeLoraAdapters(): Promise<void> {
    return RNLlama.removeLoraAdapters(this.id)
  }

  async getLoadedLoraAdapters(): Promise<
    Array<{ path: string; scaled?: number }>
  > {
    return RNLlama.getLoadedLoraAdapters(this.id)
  }

  async getConversationState(): Promise<string | null> {
    return RNLlama.getConversationState(this.id);
  }

  async restoreConversationState(state: string): Promise<boolean> {
    return RNLlama.restoreConversationState(this.id, state);
  }

  onConversationRestored(                                                                                                                  
    callback: (event: ConversationRestoredEvent) => void                                                                                   
  ): { remove: () => void } {                                                                                                              
    console.log('Setting up conversation restored listener for context:', this.id);                                                        
    if (!EventEmitter) {                                                                                                                   
      console.log('No EventEmitter available');                                                                                            
      return { remove: () => {} }                                                                                                          
    }                                                                                                                                      
    return EventEmitter.addListener(                                                                                                       
      EVENT_ON_CONVERSATION_RESTORED,                                                                                                      
      (evt: ConversationRestoredEvent) => {                                                                                                
        console.log('Received conversation restored event:', evt);                                                                         
        if (evt.contextId !== this.id) {                                                                                                   
          console.log('Event for different context, ignoring');                                                                            
          return;                                                                                                                          
        }                                                                                                                                  
        callback(evt);                                                                                                                     
      }                                                                                                                                    
    )                                                                                                                                      
  }  

  /**
   * Check if a conversation is currently being restored
   * @returns Promise<boolean> True if a restoration is in progress
   */
  async isRestoringConversation(): Promise<boolean> {
    return RNLlama.isRestoringConversation?.(this.id) || false;
  }

  async release(): Promise<void> {
    return RNLlama.releaseContext(this.id)
  }
}

export async function toggleNativeLog(enabled: boolean): Promise<void> {
  return RNLlama.toggleNativeLog(enabled)
}

export function addNativeLogListener(
  listener: (level: string, text: string) => void,
): { remove: () => void } {
  logListeners.push(listener)
  return {
    remove: () => {
      logListeners.splice(logListeners.indexOf(listener), 1)
    },
  }
}

export async function setContextLimit(limit: number): Promise<void> {
  return RNLlama.setContextLimit(limit)
}

let contextIdCounter = 0
const contextIdRandom = () =>
  process.env.NODE_ENV === 'test' ? 0 : Math.floor(Math.random() * 100000)

const modelInfoSkip = [
  // Large fields
  'tokenizer.ggml.tokens',
  'tokenizer.ggml.token_type',
  'tokenizer.ggml.merges',
]
export async function loadLlamaModelInfo(model: string): Promise<Object> {
  let path = model
  if (path.startsWith('file://')) path = path.slice(7)
  return RNLlama.modelInfo(path, modelInfoSkip)
}

const poolTypeMap = {
  // -1 is unspecified as undefined
  none: 0,
  mean: 1,
  cls: 2,
  last: 3,
  rank: 4,
}

export async function initLlama(
  {
    model,
    is_model_asset: isModelAsset,
    pooling_type: poolingType,
    lora,
    lora_list: loraList,
    contextId,
    ...rest
  }: ContextParams,
  params: ContextParams,
  onProgress?: (progress: number) => void,
): Promise<LlamaContext> {

  console.log(`Calling initLlama with params:`, JSON.stringify(params, null, 2));

  let path = model
  if (path.startsWith('file://')) path = path.slice(7)

  let loraPath = lora
  if (loraPath?.startsWith('file://')) loraPath = loraPath.slice(7)

  let loraAdapters: Array<{ path: string; scaled?: number }> = []
  if (loraList)
    loraAdapters = loraList.map((l) => ({
      path: l.path.replace(/file:\/\//, ''),
      scaled: l.scaled,
    }))

  // Use provided contextId or generate a random one
  const finalContextId = contextId || (contextIdCounter + contextIdRandom())
  contextIdCounter += 1

  let removeProgressListener: any = null
  if (onProgress) {
    removeProgressListener = EventEmitter.addListener(
      EVENT_ON_INIT_CONTEXT_PROGRESS,
      (evt: { contextId: number; progress: number }) => {
        if (evt.contextId !== contextId) return
        onProgress(evt.progress)
      },
    )
  }

  const poolType = poolTypeMap[poolingType as keyof typeof poolTypeMap]
  const {
    gpu,
    reasonNoGPU,
    model: modelDetails,
    androidLib,
  } = await RNLlama.initContext(finalContextId, {
    model: path,
    is_model_asset: !!isModelAsset,
    use_progress_callback: !!onProgress,
    pooling_type: poolType,
    lora: loraPath,
    lora_list: loraAdapters,
    ...rest,
  }).catch((err: any) => {
    removeProgressListener?.remove()
    throw err
  })
  removeProgressListener?.remove()
  
  return new LlamaContext({
    contextId: finalContextId,
    gpu,
    reasonNoGPU,
    model: modelDetails,
    androidLib,
  })
}

export async function releaseAllLlama(): Promise<void> {
  return RNLlama.releaseAllContexts()
}
